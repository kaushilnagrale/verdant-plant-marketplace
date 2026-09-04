package com.verdant.finance.service;

import com.verdant.common.domain.Money;
import com.verdant.finance.domain.PayoutCycle;
import com.verdant.finance.domain.PayoutLineItem;
import com.verdant.finance.domain.PayoutStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Repository
public class PayoutCycleRepository {

    private final JdbcTemplate jdbc;

    public PayoutCycleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Goes straight to PENDING_APPROVAL - a built cycle has nothing more to
     * compute, it's immediately ready for a human to review (Day 47.1).
     * DRAFT exists in the schema but nothing in this codebase transitions
     * through it as a distinct step.
     *
     * UNIQUE(seller_id, period_start, period_end) already exists (V30) -
     * ON CONFLICT DO NOTHING plus a lookup fallback means a job that runs
     * buildCycle() twice for the same seller/period gets back the SAME
     * cycle rather than a duplicate-key crash, the same idempotent-insert
     * shape used throughout this project.
     */
    public UUID insert(UUID sellerId, LocalDate periodStart, LocalDate periodEnd,
                        Money gross, Money withheld, Money net, String idempotencyKey) {
        jdbc.update("""
            INSERT INTO payout_cycles (seller_id, period_start, period_end, status,
                                        gross_amount, withheld_amount, net_amount, idempotency_key)
            VALUES (?, ?, ?, 'PENDING_APPROVAL', ?, ?, ?, ?)
            ON CONFLICT (seller_id, period_start, period_end) DO NOTHING
            """, sellerId, periodStart, periodEnd, gross.amount(), withheld.amount(), net.amount(), idempotencyKey);
        return jdbc.queryForObject("""
            SELECT id FROM payout_cycles WHERE seller_id = ? AND period_start = ? AND period_end = ?
            """, UUID.class, sellerId, periodStart, periodEnd);
    }

    public void insertLineItem(UUID cycleId, PayoutLineItem item) {
        jdbc.update("""
            INSERT INTO payout_line_items (payout_cycle_id, order_id, order_item_id, gross, commission, tcs, gateway_fee, claim_adjustment, net)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, cycleId, item.orderId(), item.orderItemId(), item.gross().amount(), item.commission().amount(),
            item.tcs().amount(), item.gatewayFee().amount(), item.claimAdjustment().amount(), item.net().amount());
    }

    public PayoutCycle findById(UUID cycleId) {
        var row = jdbc.queryForMap("""
            SELECT id, seller_id, period_start, period_end, status, gross_amount, withheld_amount, net_amount,
                   ledger_txn_id, provider_ref, idempotency_key, approved_by, paid_at, failure_reason
              FROM payout_cycles WHERE id = ?
            """, cycleId);

        List<PayoutLineItem> lineItems = jdbc.query("""
            SELECT order_id, order_item_id, gross, commission, tcs, gateway_fee, claim_adjustment, net
              FROM payout_line_items WHERE payout_cycle_id = ?
            """, (rs, n) -> new PayoutLineItem(
                (UUID) rs.getObject("order_id"), (UUID) rs.getObject("order_item_id"),
                Money.of(rs.getBigDecimal("gross")), Money.of(rs.getBigDecimal("commission")),
                Money.of(rs.getBigDecimal("tcs")), Money.of(rs.getBigDecimal("gateway_fee")),
                Money.of(rs.getBigDecimal("claim_adjustment")), Money.of(rs.getBigDecimal("net"))),
            cycleId);

        var paidAt = (java.sql.Timestamp) row.get("paid_at");
        return new PayoutCycle(
            (UUID) row.get("id"), (UUID) row.get("seller_id"),
            ((java.sql.Date) row.get("period_start")).toLocalDate(), ((java.sql.Date) row.get("period_end")).toLocalDate(),
            PayoutStatus.valueOf((String) row.get("status")),
            Money.of((java.math.BigDecimal) row.get("gross_amount")), Money.of((java.math.BigDecimal) row.get("withheld_amount")),
            Money.of((java.math.BigDecimal) row.get("net_amount")), (UUID) row.get("ledger_txn_id"),
            (String) row.get("provider_ref"), (String) row.get("idempotency_key"), (String) row.get("approved_by"),
            paidAt == null ? null : paidAt.toInstant(), (String) row.get("failure_reason"), lineItems);
    }

    public void markApproved(UUID cycleId, String approverId) {
        jdbc.update("UPDATE payout_cycles SET status = 'APPROVED', approved_by = ? WHERE id = ?", approverId, cycleId);
    }

    /**
     * Atomic claim, same shape used throughout this project (reservations,
     * outbox, shipment booking) - found necessary by the 8-way concurrency
     * test, not assumed: LedgerService.post() is safely idempotent under
     * concurrent callers, but nothing guarded the actual PayoutGateway.
     * transfer() call, so without this, multiple concurrent initiate()
     * calls could all pass the balance check before any of them posted,
     * then all independently call the real (or stub) payout provider -
     * exactly the double-payout risk this whole feature exists to prevent.
     * Only the caller that wins this claim (1 row updated) may proceed to
     * call the gateway; everyone else backs off.
     */
    public boolean claimForTransfer(UUID cycleId) {
        int updated = jdbc.update(
            "UPDATE payout_cycles SET status = 'INITIATED' WHERE id = ? AND status = 'APPROVED'", cycleId);
        return updated == 1;
    }

    /** Recorded the moment the ledger post succeeds, separately from markPaid() - if the transfer call that follows then fails, PayoutFailureHandler still needs to find this id to reverse it. */
    public void recordLedgerTransaction(UUID cycleId, UUID ledgerTxnId) {
        jdbc.update("UPDATE payout_cycles SET ledger_txn_id = ? WHERE id = ?", ledgerTxnId, cycleId);
    }

    public void markPaid(UUID cycleId, String providerRef) {
        jdbc.update("""
            UPDATE payout_cycles SET status = 'PAID', provider_ref = ?, paid_at = now() WHERE id = ?
            """, providerRef, cycleId);
    }

    /** Idempotent - a second call after the cycle is already FAILED is a no-op, matching the guard in PayoutFailureHandler. */
    public int markFailed(UUID cycleId, String reason) {
        return jdbc.update("""
            UPDATE payout_cycles SET status = 'FAILED', failure_reason = ? WHERE id = ? AND status <> 'FAILED'
            """, reason, cycleId);
    }
}
