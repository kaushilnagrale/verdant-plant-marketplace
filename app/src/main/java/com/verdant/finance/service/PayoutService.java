package com.verdant.finance.service;

import com.verdant.common.domain.Money;
import com.verdant.finance.domain.AccountType;
import com.verdant.finance.domain.LedgerTransaction;
import com.verdant.finance.domain.PayoutCycle;
import com.verdant.finance.domain.PayoutLineItem;
import com.verdant.finance.domain.PayoutStatus;
import com.verdant.finance.payout.PayoutGateway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Deliberately NOT @Transactional at the class/initiate() level - the same
 * real capacity lesson from ClaimResolutionService this same week:
 * LedgerService.post() already runs its own isolated REQUIRES_NEW
 * transaction, so wrapping the caller in @Transactional too just holds a
 * second connection for no benefit and risks pool exhaustion under
 * concurrency (proven by PayoutTest's own 8-way concurrent test).
 *
 * The runbook's own note that "the bank transfer happens in an outbox
 * consumer, never inline" is deliberately not followed literally here:
 * that indirection exists in real systems to avoid holding a DB
 * transaction open across a slow, unreliable network call - a real
 * concern for Shiprocket/Razorpay, but not for a synchronous in-process
 * stub with no real latency or failure profile to protect against (see
 * PayoutGateway). initiate() calls the gateway directly and reverses via
 * PayoutFailureHandler if it throws - simpler, and no less correct, given
 * what's actually being protected against here.
 */
@Service
public class PayoutService {

    private final JdbcTemplate jdbc;
    private final PayoutCycleRepository cycles;
    private final PayoutEligibility eligibility;
    private final RateConfigService rates;
    private final LedgerService ledger;
    private final AccountResolver accounts;
    private final PayoutGateway payoutGateway;
    private final PayoutFailureHandler failureHandler;

    public PayoutService(JdbcTemplate jdbc, PayoutCycleRepository cycles, PayoutEligibility eligibility,
                          RateConfigService rates, LedgerService ledger, AccountResolver accounts,
                          PayoutGateway payoutGateway, PayoutFailureHandler failureHandler) {
        this.jdbc = jdbc;
        this.cycles = cycles;
        this.eligibility = eligibility;
        this.rates = rates;
        this.ledger = ledger;
        this.accounts = accounts;
        this.payoutGateway = payoutGateway;
        this.failureHandler = failureHandler;
    }

    public PayoutCycle buildCycle(UUID sellerId, LocalDate start, LocalDate end) {
        List<Map<String, Object>> candidates = jdbc.queryForList("""
            SELECT oi.id AS order_item_id, oi.order_id, oi.line_total
              FROM order_items oi
              JOIN shipments sh ON sh.order_id = oi.order_id AND sh.seller_id = oi.seller_id
             WHERE oi.seller_id = ? AND sh.delivered_at IS NOT NULL AND sh.delivered_at::date BETWEEN ? AND ?
            """, sellerId, start, end);

        List<PayoutLineItem> lines = candidates.stream()
            .filter(row -> eligibility.isEligible((UUID) row.get("order_item_id")))
            .map(row -> toLineItem((UUID) row.get("order_id"), (UUID) row.get("order_item_id"),
                Money.of((BigDecimal) row.get("line_total")), sellerId, end))
            .toList();

        Money gross = lines.stream().map(PayoutLineItem::gross).reduce(Money.ZERO, Money::plus);
        Money withheld = lines.stream()
            .map(l -> l.commission().plus(l.tcs()).plus(l.gatewayFee()))
            .reduce(Money.ZERO, Money::plus);
        Money net = lines.stream().map(PayoutLineItem::net).reduce(Money.ZERO, Money::plus);

        String idempotencyKey = "payout:" + sellerId + ":" + start + ":" + end;
        UUID cycleId = cycles.insert(sellerId, start, end, gross, withheld, net, idempotencyKey);

        // insert() is idempotent (ON CONFLICT DO NOTHING + lookup) - only
        // add line items the first time, or a repeat buildCycle() call for
        // the same seller/period would duplicate them under the same cycle.
        Long existingLines = jdbc.queryForObject(
            "SELECT count(*) FROM payout_line_items WHERE payout_cycle_id = ?", Long.class, cycleId);
        if (existingLines == 0) {
            lines.forEach(l -> cycles.insertLineItem(cycleId, l));
        }

        return cycles.findById(cycleId);
    }

    public PayoutCycle approve(UUID cycleId, String approverId) {
        cycles.markApproved(cycleId, approverId);
        return cycles.findById(cycleId);
    }

    /**
     * Money moves only after explicit approval.
     *
     * Two real races found by the 8-way concurrency test, not assumed
     * safe: (1) the balance check below reads a snapshot that can go
     * stale between the read and the ledger post - if another concurrent
     * caller finishes an entire payout in between, this caller's own
     * check can see a balance that's already been spent and throw a
     * spurious InsufficientPayableException instead of recognizing the
     * payout as already done. (2) LedgerService.post() is safely
     * idempotent under concurrent callers, but nothing guarded the actual
     * PayoutGateway.transfer() call itself - see claimForTransfer()'s own
     * doc comment for why that's the real double-payout risk.
     */
    public PayoutCycle initiate(UUID cycleId, String actorId) {
        PayoutCycle cycle = cycles.findById(cycleId);

        if (cycle.status() == PayoutStatus.PAID) {
            return cycle; // idempotent - a retried initiate() after success is a no-op, not a re-transfer
        }
        cycle.requireStatus(PayoutStatus.APPROVED);

        Money available = ledger.balance(accounts.sellerPayable(cycle.sellerId()));
        if (cycle.netAmount().isGreaterThan(available)) {
            // Someone else may have completed this exact payout between
            // our read above and this check - re-confirm before concluding
            // genuine insufficiency, rather than surfacing a race as a
            // false balance failure.
            PayoutCycle recheck = cycles.findById(cycleId);
            if (recheck.status() == PayoutStatus.PAID) {
                return recheck;
            }
            throw new InsufficientPayableException(cycle.netAmount(), available);
        }

        LedgerTransaction txn = ledger.post(cycle.idempotencyKey(), "PAYOUT", cycleId.toString(),
            List.of(
                new LedgerService.EntrySpec(accounts.sellerPayable(cycle.sellerId()), cycle.netAmount().negate()),
                new LedgerService.EntrySpec(accounts.platform(AccountType.PLATFORM_CASH), cycle.netAmount())));

        // Recorded before the transfer attempt, deliberately - if the
        // transfer below throws, PayoutFailureHandler needs this id
        // already on the row to find and reverse the posting it just made.
        cycles.recordLedgerTransaction(cycleId, txn.getId());

        if (!cycles.claimForTransfer(cycleId)) {
            return cycles.findById(cycleId); // another caller is already handling (or already finished) the real transfer
        }

        String providerRef;
        try {
            providerRef = payoutGateway.transfer(cycle.idempotencyKey(), cycle.sellerId(), cycle.netAmount());
        } catch (RuntimeException e) {
            failureHandler.handleFailure(cycleId, e.getMessage());
            throw e;
        }

        cycles.markPaid(cycleId, providerRef);
        return cycles.findById(cycleId);
    }

    /** Recomputed at item granularity - see PayoutLineItem's own doc comment for why this can differ from the original settlement by a paisa or two of independent rounding, harmlessly. */
    private PayoutLineItem toLineItem(UUID orderId, UUID orderItemId, Money gross, UUID sellerId, LocalDate asOf) {
        Money commission = gross.multiply(rates.rate("COMMISSION", sellerId, asOf));
        Money tcs = gross.multiply(rates.rate("TCS", null, asOf));
        Money gatewayFee = gross.multiply(rates.rate("GATEWAY_FEE", null, asOf));
        Money claimAdjustment = resolvedSellerFaultAdjustment(orderItemId);
        Money net = gross.minus(commission).minus(tcs).minus(gatewayFee).plus(claimAdjustment);
        return new PayoutLineItem(orderId, orderItemId, gross, commission, tcs, gatewayFee, claimAdjustment, net);
    }

    /** A RESOLVED seller-fault claim already reduced sellerPayable via ClaimResolutionService - reflected here from the claim's own recorded amount, not re-derived, so the statement matches what actually happened. */
    private Money resolvedSellerFaultAdjustment(UUID orderItemId) {
        List<BigDecimal> amounts = jdbc.queryForList("""
            SELECT resolution_amount FROM claims WHERE order_item_id = ? AND status = 'RESOLVED' AND fault = 'SELLER'
            """, BigDecimal.class, orderItemId);
        return amounts.isEmpty() ? Money.ZERO : Money.of(amounts.get(0)).negate();
    }
}
