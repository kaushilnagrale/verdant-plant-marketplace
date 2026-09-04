package com.verdant.claims.service;

import com.verdant.claims.domain.Claim;
import com.verdant.claims.domain.ClaimStatus;
import com.verdant.claims.domain.Resolution;
import com.verdant.common.domain.Money;
import com.verdant.finance.domain.AccountType;
import com.verdant.finance.domain.LedgerTransaction;
import com.verdant.finance.service.AccountResolver;
import com.verdant.finance.service.LedgerService;
import com.verdant.finance.service.LedgerService.EntrySpec;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Resolution posts to the ledger at resolution time, not payout time -
 * a seller's payable balance always reflects open liability, and payouts
 * (Day 44) never pay out money that's about to be clawed back.
 *
 * Deliberately NOT @Transactional at this level - a real capacity bug
 * found by the 10-way concurrency test, not assumed safe: LedgerService.
 * post() already runs its own isolated REQUIRES_NEW insert (a genuinely
 * separate physical connection), so wrapping resolve() itself in
 * @Transactional meant each concurrent caller held TWO connections at
 * once (its own outer transaction, suspended, plus post()'s REQUIRES_NEW
 * one) - ten concurrent resolve() calls could need up to twenty
 * connections against Boot's default pool of ten, and reliably exhausted
 * it (a real SQLTransientConnectionException, reproduced twice, not a
 * one-off). None of the three steps below actually need a shared
 * transaction: findById() is a plain read, post() is already atomic and
 * idempotent on its own, and recordResolution() is one UPDATE statement -
 * the same "small saga, not one big transaction" shape CheckoutService
 * already uses. Concurrency safety instead comes entirely from
 * LedgerService.post()'s own proven idempotency (Day 26), keyed by
 * "claim:" + claimId - ten concurrent resolve() calls for the same claim
 * all resolve to the SAME ledger transaction id, so the redundant
 * recordResolution() writes that follow are writing identical values,
 * not racing to decide different ones.
 *
 * debitCourierRecoverable was referenced in the runbook's own switch
 * statement but never actually defined anywhere in it - a real gap in the
 * source material. Designed here to mirror debitPlatform's shape: the
 * platform still pays the buyer immediately (REFUNDS_PAYABLE), but tracks
 * what the courier owes back separately (COURIER_RECEIVABLE) rather than
 * eating the cost as a platform expense - recovering it later via an SLA
 * back-charge is a real follow-up feature, not built this week.
 */
@Service
public class ClaimResolutionService {

    private final ClaimRepository claims;
    private final LedgerService ledger;
    private final AccountResolver accounts;
    private final JdbcTemplate jdbc;

    public ClaimResolutionService(ClaimRepository claims, LedgerService ledger, AccountResolver accounts, JdbcTemplate jdbc) {
        this.claims = claims;
        this.ledger = ledger;
        this.accounts = accounts;
        this.jdbc = jdbc;
    }

    public Claim resolve(UUID claimId, Resolution resolution, Money amount, String actorId) {
        Claim claim = claims.findById(claimId);

        if (claim.status() == ClaimStatus.RESOLVED) {
            return claim; // idempotent
        }

        LedgerTransaction txn = switch (claim.fault()) {
            case SELLER -> debitSeller(claim, amount);
            case COURIER -> debitCourierRecoverable(claim, amount);
            case PLATFORM, BUYER, UNDETERMINED -> debitPlatform(claim, amount); // platform absorbs
        };

        claims.recordResolution(claimId, resolution, amount, txn.getId(), actorId);

        // Resolution.REPLACEMENT would also need a new order/shipment
        // created and shipped - real, separate feature, not built this
        // week. The ledger/claim side above is correct and complete
        // regardless of which resolution type was chosen; only the actual
        // re-shipment is the open piece.

        return claims.findById(claimId);
    }

    /** Seller fault: reduce what we owe them. */
    private LedgerTransaction debitSeller(Claim claim, Money amount) {
        UUID sellerId = sellerIdFor(claim.orderItemId());
        return ledger.post("claim:" + claim.id(), "CLAIM_SELLER_FAULT", claim.id().toString(),
            List.of(
                new EntrySpec(accounts.sellerPayable(sellerId), amount.negate()),
                new EntrySpec(accounts.platform(AccountType.REFUNDS_PAYABLE), amount)));
    }

    /** Platform fault (also the buyer-fault/undetermined fallback - the platform absorbs it either way): platform recognizes it as an expense. */
    private LedgerTransaction debitPlatform(Claim claim, Money amount) {
        return ledger.post("claim:" + claim.id(), "CLAIM_PLATFORM_ABSORBED", claim.id().toString(),
            List.of(
                new EntrySpec(accounts.platform(AccountType.CLAIMS_EXPENSE), amount),
                new EntrySpec(accounts.platform(AccountType.REFUNDS_PAYABLE), amount.negate())));
    }

    /** Courier fault: platform still pays the buyer now, but tracks a receivable from the courier rather than eating the cost itself. */
    private LedgerTransaction debitCourierRecoverable(Claim claim, Money amount) {
        return ledger.post("claim:" + claim.id(), "CLAIM_COURIER_FAULT", claim.id().toString(),
            List.of(
                new EntrySpec(accounts.platform(AccountType.COURIER_RECEIVABLE), amount),
                new EntrySpec(accounts.platform(AccountType.REFUNDS_PAYABLE), amount.negate())));
    }

    private UUID sellerIdFor(UUID orderItemId) {
        return jdbc.queryForObject("SELECT seller_id FROM order_items WHERE id = ?", UUID.class, orderItemId);
    }
}
