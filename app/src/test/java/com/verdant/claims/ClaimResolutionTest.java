package com.verdant.claims;

import com.verdant.claims.domain.Claim;
import com.verdant.claims.domain.ClaimStatus;
import com.verdant.claims.domain.ClaimType;
import com.verdant.claims.domain.Fault;
import com.verdant.claims.domain.ItemFacts;
import com.verdant.claims.domain.Resolution;
import com.verdant.claims.domain.ShipmentFacts;
import com.verdant.claims.service.ClaimResolutionService;
import com.verdant.claims.service.ClaimService;
import com.verdant.claims.service.ClaimWindowClosedException;
import com.verdant.claims.service.FaultAttributor;
import com.verdant.claims.service.InsufficientEvidenceException;
import com.verdant.common.domain.Money;
import com.verdant.common.testing.AllocationFixtures;
import com.verdant.common.testing.IntegrationTestBase;
import com.verdant.finance.domain.AccountType;
import com.verdant.finance.repository.LedgerTransactionRepository;
import com.verdant.finance.service.AccountResolver;
import com.verdant.finance.service.LedgerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClaimResolutionTest extends IntegrationTestBase {

    @Autowired private ClaimResolutionService resolution;
    @Autowired private ClaimService claimService;
    @Autowired private FaultAttributor attributor;
    @Autowired private LedgerService ledger;
    @Autowired private AccountResolver accounts;
    @Autowired private LedgerTransactionRepository ledgerTxnRepo;
    @Autowired private JdbcTemplate jdbc;

    private UUID sellerId;

    private UUID newOrderItem(Fault fault, String amount) {
        AllocationFixtures.seedLocalPincodeZone(jdbc);
        sellerId = AllocationFixtures.seedApprovedSeller(jdbc, "Claims Test Nursery " + UUID.randomUUID());
        UUID canonicalProductId = AllocationFixtures.seedCanonicalProduct(jdbc, "Claims Test Plant");
        UUID listingId = AllocationFixtures.seedListing(jdbc, sellerId, canonicalProductId, "Claims Test Plant", amount, 5);

        UUID orderId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO orders (id, buyer_email, status, total_amount, currency, expires_at, destination_pincode)
            VALUES (?, 'claims-buyer@example.com', 'PAID', ?, 'INR', now() + interval '15 minutes', ?)
            """, orderId, new java.math.BigDecimal(amount), AllocationFixtures.LOCAL_PINCODE);

        UUID orderItemId = jdbc.queryForObject("""
            INSERT INTO order_items (order_id, listing_id, seller_id, quantity, unit_price, line_total)
            VALUES (?, ?, ?, 1, ?, ?) RETURNING id
            """, UUID.class, orderId, listingId, sellerId, new java.math.BigDecimal(amount), new java.math.BigDecimal(amount));

        UUID shipmentId = jdbc.queryForObject("""
            INSERT INTO shipments (order_id, seller_id, status, origin_pincode, destination_pincode,
                                    transit_days, planned_dispatch_date, dispatched_at, delivered_at)
            VALUES (?, ?, 'DISPATCHED', '411001', ?, 4, CURRENT_DATE, now() - interval '4 days', now() - interval '1 hour')
            RETURNING id
            """, UUID.class, orderId, sellerId, AllocationFixtures.LOCAL_PINCODE);

        jdbc.update("""
            INSERT INTO claim_windows (order_item_id, opens_at, closes_at)
            VALUES (?, now() - interval '1 hour', now() + interval '71 hours')
            """, orderItemId);

        this.lastShipmentId = shipmentId;
        return orderItemId;
    }

    private UUID lastShipmentId;

    /** Bypasses real attribution on purpose - this test suite is about resolution/ledger correctness, not the heuristic (FaultAttributor has its own coverage below and via DispatchGateTest-adjacent scenarios). */
    private UUID claimWithFault(Fault fault, String amount) {
        UUID orderItemId = newOrderItem(fault, amount);
        return jdbc.queryForObject("""
            INSERT INTO claims (order_item_id, shipment_id, buyer_subject_id, type, status, fault, fault_confidence)
            VALUES (?, ?, 'claims-buyer@example.com', 'DEAD_ON_ARRIVAL', 'APPROVED', ?, 0.90)
            RETURNING id
            """, UUID.class, orderItemId, lastShipmentId, fault.name());
    }

    private UUID sellerPayable(UUID seller) {
        return accounts.sellerPayable(seller);
    }

    private UUID platform(AccountType type) {
        return accounts.platform(type);
    }

    @Test
    void claimWithSellerFault_debitsSellerPayable_notPlatformAccount() {
        UUID claimId = claimWithFault(Fault.SELLER, "349.00");
        Money payableBefore = ledger.balance(sellerPayable(sellerId));
        // CLAIMS_EXPENSE is a shared singleton platform account (like every
        // other test in this class touches) - asserting it against an
        // absolute Money.ZERO is exactly the "shared singleton account"
        // pollution this project has hit before; captured as a delta
        // instead, same as the other two fault-branch tests below.
        Money expenseBefore = ledger.balance(platform(AccountType.CLAIMS_EXPENSE));

        resolution.resolve(claimId, Resolution.REFUND, Money.of("349.00"), "ops-1");

        assertThat(ledger.balance(sellerPayable(sellerId))).isEqualTo(payableBefore.minus(Money.of("349.00")));
        assertThat(ledger.balance(platform(AccountType.CLAIMS_EXPENSE))).isEqualTo(expenseBefore); // untouched by a seller-fault claim
    }

    @Test
    void claimWithPlatformFault_absorbsCost_andLeavesSellerPayableUntouched() {
        UUID claimId = claimWithFault(Fault.PLATFORM, "349.00");
        Money before = ledger.balance(sellerPayable(sellerId));
        Money expenseBefore = ledger.balance(platform(AccountType.CLAIMS_EXPENSE));

        resolution.resolve(claimId, Resolution.REFUND, Money.of("349.00"), "ops-1");

        assertThat(ledger.balance(sellerPayable(sellerId))).isEqualTo(before);
        assertThat(ledger.balance(platform(AccountType.CLAIMS_EXPENSE))).isEqualTo(expenseBefore.plus(Money.of("349.00")));
    }

    @Test
    void claimWithCourierFault_tracksCourierReceivable_notPlatformExpense() {
        UUID claimId = claimWithFault(Fault.COURIER, "349.00");
        Money expenseBefore = ledger.balance(platform(AccountType.CLAIMS_EXPENSE));
        Money receivableBefore = ledger.balance(platform(AccountType.COURIER_RECEIVABLE));

        resolution.resolve(claimId, Resolution.REFUND, Money.of("349.00"), "ops-1");

        assertThat(ledger.balance(platform(AccountType.CLAIMS_EXPENSE))).isEqualTo(expenseBefore);
        assertThat(ledger.balance(platform(AccountType.COURIER_RECEIVABLE))).isEqualTo(receivableBefore.plus(Money.of("349.00")));
    }

    @Test
    void weatherOverrideByPlatform_attributesFaultToPlatform() {
        UUID orderItemId = newOrderItem(Fault.UNDETERMINED, "349.00");
        jdbc.update("""
            INSERT INTO dispatch_holds (shipment_id, reason, max_hold_until, released_at, released_by, shadow)
            VALUES (?, 'HEAT', now() + interval '1 day', now(), 'buyer_override', FALSE)
            """, lastShipmentId);

        var facts = jdbc.queryForObject("""
            SELECT planned_dispatch_date, dispatched_at, delivered_at, transit_days FROM shipments WHERE id = ?
            """, (rs, n) -> new ShipmentFacts(lastShipmentId, rs.getDate("planned_dispatch_date").toLocalDate(),
                rs.getTimestamp("dispatched_at").toInstant(), rs.getTimestamp("delivered_at").toInstant(),
                rs.getInt("transit_days")), lastShipmentId);

        var attribution = attributor.attribute(facts, new ItemFacts(orderItemId, 24));

        assertThat(attribution.fault()).isEqualTo(Fault.PLATFORM);
        assertThat(attribution.needsHumanReview()).isFalse();
    }

    @Test
    void lowConfidenceAttribution_routesToHumanReview_andDoesNotPost() {
        UUID orderItemId = newOrderItem(Fault.UNDETERMINED, "349.00");
        // Delivered only 1 day later than estimated (not the >2 days that
        // would attribute to the courier) and dispatched on time - none of
        // FaultAttributor's confident rules fire, so it falls through to
        // UNDETERMINED at 0.0 confidence.
        jdbc.update("""
            UPDATE shipments SET dispatched_at = planned_dispatch_date::timestamptz,
                                  delivered_at = planned_dispatch_date::timestamptz + interval '5 days',
                                  transit_days = 4
             WHERE id = ?
            """, lastShipmentId);

        UUID claimId = claimService.submit(orderItemId, ClaimType.DEAD_ON_ARRIVAL, "claims-buyer@example.com", List.of("s3://claim-photo-1"));
        Claim claim = loadClaim(claimId);

        assertThat(claim.status()).isEqualTo(ClaimStatus.UNDER_REVIEW);
        assertThat(claim.ledgerTxnId()).isNull();
    }

    @Test
    void claimSubmittedAfterWindowClosed_isRejected() {
        UUID orderItemId = newOrderItem(Fault.UNDETERMINED, "349.00");
        jdbc.update("UPDATE claim_windows SET opens_at = now() - interval '10 days', closes_at = now() - interval '7 days' WHERE order_item_id = ?", orderItemId);

        assertThatThrownBy(() -> claimService.submit(orderItemId, ClaimType.DEAD_ON_ARRIVAL, "claims-buyer@example.com", List.of("s3://photo")))
            .isInstanceOf(ClaimWindowClosedException.class);
    }

    @Test
    void claimWithoutPhotos_isRejected() {
        UUID orderItemId = newOrderItem(Fault.UNDETERMINED, "349.00");
        assertThatThrownBy(() -> claimService.submit(orderItemId, ClaimType.DEAD_ON_ARRIVAL, "claims-buyer@example.com", List.of()))
            .isInstanceOf(InsufficientEvidenceException.class);
    }

    @Test
    void sameOrderItem_cannotBeRefundedTwice_underConcurrency() throws Exception {
        UUID claimId = claimWithFault(Fault.SELLER, "349.00");

        runInParallel(10, () -> resolution.resolve(claimId, Resolution.REFUND, Money.of("349.00"), "ops-1"));

        assertThat(ledgerTxnRepo.countByReference(claimId.toString())).isEqualTo(1L);
    }

    /**
     * Adapted: this project has no discount/coupon system at all (checked
     * across every prior week - unit_price on order_items is always
     * exactly what was charged, there is no separate "list price" to
     * diverge from). The runbook's own version of this test exercises a
     * discount-allocation feature that doesn't exist here. What's actually
     * preserved and tested: resolve() persists exactly the amount it's
     * given, sourced from the real charged amount (order_items.line_total),
     * never re-derived from the listing's current price - the same
     * underlying principle ("refund what was charged"), the part of it
     * that's real in this codebase.
     */
    @Test
    void refundAmount_matchesWhatWasActuallyCharged_notTheListingsCurrentPrice() {
        UUID claimId = claimWithFault(Fault.SELLER, "349.00");
        jdbc.update("UPDATE listings SET price_amount = 999.00 WHERE id = (SELECT listing_id FROM order_items WHERE id = (SELECT order_item_id FROM claims WHERE id = ?))", claimId);

        Claim resolved = resolution.resolve(claimId, Resolution.REFUND, Money.of("349.00"), "ops-1");

        assertThat(resolved.resolutionAmount()).isEqualTo(Money.of("349.00")); // not 999.00
    }

    private Claim loadClaim(UUID claimId) {
        return jdbc.queryForObject("""
            SELECT id, order_item_id, shipment_id, buyer_subject_id, type, status, fault, fault_confidence,
                   resolution, resolution_amount, ledger_txn_id, submitted_at, resolved_at, resolved_by
              FROM claims WHERE id = ?
            """, (rs, n) -> {
                String faultRaw = rs.getString("fault");
                String resolutionRaw = rs.getString("resolution");
                var resolutionAmount = rs.getBigDecimal("resolution_amount");
                var resolvedAt = rs.getTimestamp("resolved_at");
                var confidence = rs.getBigDecimal("fault_confidence");
                return new Claim(
                    (UUID) rs.getObject("id"), (UUID) rs.getObject("order_item_id"), (UUID) rs.getObject("shipment_id"),
                    rs.getString("buyer_subject_id"), ClaimType.valueOf(rs.getString("type")),
                    ClaimStatus.valueOf(rs.getString("status")),
                    faultRaw == null ? null : Fault.valueOf(faultRaw),
                    confidence,
                    resolutionRaw == null ? null : Resolution.valueOf(resolutionRaw),
                    resolutionAmount == null ? null : Money.of(resolutionAmount),
                    (UUID) rs.getObject("ledger_txn_id"),
                    rs.getTimestamp("submitted_at").toInstant(),
                    resolvedAt == null ? null : resolvedAt.toInstant(),
                    rs.getString("resolved_by"));
            }, claimId);
    }

    private <T> List<T> runInParallel(int n, Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            futures.add(pool.submit(() -> { start.await(); return task.call(); }));
        }
        start.countDown();

        List<T> results = new java.util.ArrayList<>();
        for (Future<T> f : futures) {
            try {
                results.add(f.get());
            } catch (ExecutionException e) {
                pool.shutdown();
                throw (Exception) e.getCause();
            }
        }
        pool.shutdown();
        return results;
    }
}
