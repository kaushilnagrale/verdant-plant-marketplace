package com.verdant.finance;

import com.verdant.common.domain.Money;
import com.verdant.common.testing.AllocationFixtures;
import com.verdant.common.testing.IntegrationTestBase;
import com.verdant.finance.domain.PayoutStatus;
import com.verdant.finance.service.AccountResolver;
import com.verdant.finance.service.LedgerService;
import com.verdant.finance.service.PayoutCycleBuildJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * sellersWithDeliveriesIn() queries across every APPROVED seller in the
 * whole database, not one scoped to this test - real seed sellers
 * (Test Nursery, Blue Leaf Nursery) and leftover fixtures from other test
 * files genuinely exist alongside whatever this test creates. Every
 * assertion here is scoped to this test's own freshly-created sellerId,
 * never a global "exactly N cycles" count.
 *
 * Unlike ReconciliationTest (Day 46), the period here is NOT salted into
 * an arbitrary far date: PayoutEligibility.isEligible() genuinely depends
 * on real elapsed wall-clock time (the 72h claim window must have
 * actually passed relative to Instant.now()), so pushing dates into the
 * future - the technique that worked for reconciliation's own
 * label-only date comparisons - would make every item permanently
 * ineligible instead. A fixed 30-days-ago window is used instead: safely
 * real past (comfortably clears the 72h window), and far enough from
 * every other test's now()-relative (single-digit-days) fixtures to
 * avoid collision without needing per-run salting at all - unlike
 * ReconciliationTest's orders-table scan, this test's own rows are
 * already uniquely scoped by a fresh sellerId every run, so
 * UNIQUE(seller_id, period_start, period_end) can never collide with a
 * PRIOR run of this same file regardless of reusing the same dates.
 */
class PayoutCycleBuildJobTest extends IntegrationTestBase {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Autowired private PayoutCycleBuildJob buildJob;
    @Autowired private LedgerService ledger;
    @Autowired private AccountResolver accounts;
    @Autowired private CacheManager cacheManager;
    @Autowired private JdbcTemplate jdbc;

    private final LocalDate periodStart = LocalDate.now(IST).minusDays(30);
    private final LocalDate periodEnd = periodStart.plusDays(6);

    private UUID sellerId;

    @BeforeEach
    void seed() {
        cacheManager.getCache("flags").evict("auto_payout");
        jdbc.update("UPDATE feature_flags SET enabled = false WHERE name = 'auto_payout'");
        AllocationFixtures.seedLocalPincodeZone(jdbc);
        sellerId = AllocationFixtures.seedApprovedSeller(jdbc, "Build Job Test Nursery " + UUID.randomUUID());
    }

    /** Delivered comfortably inside the period and past the 72h claim window - real order/order_item/shipment/claim_window rows, same shape PayoutTest itself already establishes. */
    private UUID deliverEligibleItem() {
        return deliverEligibleItemFor(sellerId);
    }

    private UUID deliverEligibleItemFor(UUID forSellerId) {
        Instant deliveredAt = periodStart.plusDays(2).atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
        UUID canonicalProductId = AllocationFixtures.seedCanonicalProduct(jdbc, "Build Job Test Plant " + UUID.randomUUID());
        UUID listingId = AllocationFixtures.seedListing(jdbc, forSellerId, canonicalProductId, "Build Job Test Plant", "299.00", 5);

        UUID orderId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO orders (id, buyer_email, status, total_amount, currency, expires_at, destination_pincode)
            VALUES (?, 'build-job-buyer@example.com', 'PAID', 299.00, 'INR', now() + interval '15 minutes', ?)
            """, orderId, AllocationFixtures.LOCAL_PINCODE);

        UUID orderItemId = jdbc.queryForObject("""
            INSERT INTO order_items (order_id, listing_id, seller_id, quantity, unit_price, line_total)
            VALUES (?, ?, ?, 1, 299.00, 299.00) RETURNING id
            """, UUID.class, orderId, listingId, forSellerId);

        jdbc.update("""
            INSERT INTO shipments (order_id, seller_id, status, origin_pincode, destination_pincode,
                                    transit_days, planned_dispatch_date, dispatched_at, delivered_at)
            VALUES (?, ?, 'DELIVERED', '411001', ?, 4, ?, ?, ?)
            """, orderId, forSellerId, AllocationFixtures.LOCAL_PINCODE,
            periodStart.plusDays(1), java.sql.Timestamp.from(deliveredAt), java.sql.Timestamp.from(deliveredAt));

        jdbc.update("""
            INSERT INTO claim_windows (order_item_id, opens_at, closes_at) VALUES (?, ?, ?)
            """, orderItemId, java.sql.Timestamp.from(deliveredAt),
            java.sql.Timestamp.from(deliveredAt.plusSeconds(72 * 3600)));

        return orderItemId;
    }

    private List<String> statusesForSeller() {
        return statusesFor(sellerId);
    }

    private List<String> statusesFor(UUID forSellerId) {
        return jdbc.queryForList(
            "SELECT status FROM payout_cycles WHERE seller_id = ? AND period_start = ? AND period_end = ?",
            String.class, forSellerId, periodStart, periodEnd);
    }

    @Test
    void sellerWithEligibleDeliveries_getsCycleBuilt_pendingApproval() {
        deliverEligibleItem();

        buildJob.buildCyclesFor(periodStart, periodEnd);

        List<String> statuses = statusesForSeller();
        assertThat(statuses).containsExactly(PayoutStatus.PENDING_APPROVAL.name());
    }

    @Test
    void sellerWithNoDeliveries_getsNoCycle() {
        // Nothing delivered for this seller in the window at all - the
        // coarse sellersWithDeliveriesIn() pre-filter should exclude them
        // before buildCycle() is ever called.
        buildJob.buildCyclesFor(periodStart, periodEnd);

        assertThat(statusesForSeller()).isEmpty();
    }

    @Test
    void rerunForSamePeriod_isIdempotent_doesNotDuplicateTheCycle() {
        deliverEligibleItem();

        buildJob.buildCyclesFor(periodStart, periodEnd);
        buildJob.buildCyclesFor(periodStart, periodEnd);

        // UNIQUE(seller_id, period_start, period_end) plus buildCycle()'s
        // own idempotent insert (Day 44) - a second run must not create a
        // second row nor duplicate this seller's line items.
        assertThat(statusesForSeller()).hasSize(1);
        Integer lineItemCount = jdbc.queryForObject("""
            SELECT count(*) FROM payout_line_items pli
              JOIN payout_cycles pc ON pc.id = pli.payout_cycle_id
             WHERE pc.seller_id = ? AND pc.period_start = ? AND pc.period_end = ?
            """, Integer.class, sellerId, periodStart, periodEnd);
        assertThat(lineItemCount).isEqualTo(1);
    }

    @Test
    void autoPayoutFlagOff_leavesCycleForManualApproval() {
        deliverEligibleItem();
        fundSellerPayable("299.00"); // even with money available, the flag being off must still require a human

        buildJob.buildCyclesFor(periodStart, periodEnd);

        assertThat(statusesForSeller()).containsExactly(PayoutStatus.PENDING_APPROVAL.name());
    }

    @Test
    void autoPayoutFlagOn_approvesAndInitiatesAutomatically() {
        deliverEligibleItem();
        fundSellerPayable("299.00");
        jdbc.update("UPDATE feature_flags SET enabled = true WHERE name = 'auto_payout'");
        cacheManager.getCache("flags").evict("auto_payout");

        buildJob.buildCyclesFor(periodStart, periodEnd);

        assertThat(statusesForSeller()).containsExactly(PayoutStatus.PAID.name());
    }

    /** Real ledger funding, matching PayoutTest's own established helper - a real settlement is what normally puts money in sellerPayable. */
    private void fundSellerPayable(String amount) {
        fundSellerPayableFor(sellerId, amount);
    }

    private void fundSellerPayableFor(UUID forSellerId, String amount) {
        UUID buyerId = UUID.randomUUID();
        ledger.post("build-job-fund:" + UUID.randomUUID(), "TEST_FUNDING", "test",
            List.of(
                new LedgerService.EntrySpec(accounts.buyerReceivable(buyerId), Money.of(amount).negate()),
                new LedgerService.EntrySpec(accounts.sellerPayable(forSellerId), Money.of(amount))));
    }

    @Test
    void oneSellerFailingAutoInitiate_doesNotBlockAnotherSellersPayout() {
        // Real bug, found by this exact scenario (Day 47): the original
        // buildCyclesFor() had no per-seller isolation - one seller
        // throwing (here, genuinely insufficient balance) took down the
        // rest of the whole weekly batch. A second, unrelated, PROPERLY
        // funded seller must still get paid in the same run.
        UUID strugglingSeller = AllocationFixtures.seedApprovedSeller(jdbc, "Build Job Struggling Nursery " + UUID.randomUUID());
        deliverEligibleItemFor(strugglingSeller); // never funded - initiate() will genuinely throw

        deliverEligibleItem();
        fundSellerPayable("299.00");

        jdbc.update("UPDATE feature_flags SET enabled = true WHERE name = 'auto_payout'");
        cacheManager.getCache("flags").evict("auto_payout");

        buildJob.buildCyclesFor(periodStart, periodEnd);

        // approve() itself succeeds (the cycle genuinely was
        // PENDING_APPROVAL) - initiate() is what then throws on the
        // balance check, so this seller's cycle lands at APPROVED, not
        // PAID, and not reverted - exactly the state a human would find
        // it in and need to investigate. The point of this test is the
        // OTHER seller, unaffected by this one's failure.
        assertThat(statusesFor(strugglingSeller)).containsExactly(PayoutStatus.APPROVED.name());
        assertThat(statusesForSeller()).containsExactly(PayoutStatus.PAID.name());
    }
}
