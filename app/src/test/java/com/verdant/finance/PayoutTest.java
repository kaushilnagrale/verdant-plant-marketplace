package com.verdant.finance;

import com.verdant.common.domain.Money;
import com.verdant.common.testing.AllocationFixtures;
import com.verdant.common.testing.IntegrationTestBase;
import com.verdant.finance.domain.PayoutCycle;
import com.verdant.finance.domain.PayoutStatus;
import com.verdant.finance.payout.StubPayoutGateway;
import com.verdant.finance.repository.LedgerTransactionRepository;
import com.verdant.finance.service.AccountResolver;
import com.verdant.finance.service.InsufficientPayableException;
import com.verdant.finance.service.LedgerService;
import com.verdant.finance.service.PayoutFailureHandler;
import com.verdant.finance.service.PayoutService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
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

class PayoutTest extends IntegrationTestBase {

    @Autowired private PayoutService payouts;
    @Autowired private PayoutFailureHandler failureHandler;
    @Autowired private LedgerService ledger;
    @Autowired private AccountResolver accounts;
    @Autowired private LedgerTransactionRepository ledgerTxnRepo;
    @Autowired private StubPayoutGateway payoutGateway;
    @Autowired private JdbcTemplate jdbc;

    private UUID sellerId;
    private final LocalDate periodStart = LocalDate.now().minusDays(7);
    private final LocalDate periodEnd = LocalDate.now();

    @BeforeEach
    void seed() {
        payoutGateway.reset();
        AllocationFixtures.seedLocalPincodeZone(jdbc);
        sellerId = AllocationFixtures.seedApprovedSeller(jdbc, "Payout Test Nursery " + UUID.randomUUID());
    }

    /** Real ledger funding, not a fabricated balance - a real settlement is what normally puts money in sellerPayable; this is the same effect via a direct test-only posting. */
    private void fundSellerPayable(String amount) {
        UUID buyerId = UUID.randomUUID();
        ledger.post("payout-test-fund:" + UUID.randomUUID(), "TEST_FUNDING", "test",
            List.of(
                new LedgerService.EntrySpec(accounts.buyerReceivable(buyerId), Money.of(amount).negate()),
                new LedgerService.EntrySpec(accounts.sellerPayable(sellerId), Money.of(amount))));
    }

    private UUID approvedCycle(String amount) {
        fundSellerPayable(amount);
        return insertCycle(amount, PayoutStatus.APPROVED);
    }

    private UUID approvedCycleWithAmountExceedingBalance() {
        fundSellerPayable("100.00");
        return insertCycle("5000.00", PayoutStatus.APPROVED);
    }

    private UUID insertCycle(String netAmount, PayoutStatus status) {
        return jdbc.queryForObject("""
            INSERT INTO payout_cycles (seller_id, period_start, period_end, status, gross_amount, withheld_amount, net_amount, idempotency_key)
            VALUES (?, ?, ?, ?, ?, 0, ?, ?) RETURNING id
            """, UUID.class, sellerId, periodStart, periodEnd, status.name(),
            new BigDecimal(netAmount), new BigDecimal(netAmount), "payout:" + UUID.randomUUID());
    }

    /** A real order_item + shipment, delivered at the given instant - buildCycle()'s own eligibility window/COD checks operate on this. */
    private UUID deliverItem(java.time.Instant deliveredAt) {
        UUID canonicalProductId = AllocationFixtures.seedCanonicalProduct(jdbc, "Payout Test Plant");
        UUID listingId = AllocationFixtures.seedListing(jdbc, sellerId, canonicalProductId, "Payout Test Plant", "349.00", 5);

        UUID orderId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO orders (id, buyer_email, status, total_amount, currency, expires_at, destination_pincode)
            VALUES (?, 'payout-buyer@example.com', 'PAID', 349.00, 'INR', now() + interval '15 minutes', ?)
            """, orderId, AllocationFixtures.LOCAL_PINCODE);

        UUID orderItemId = jdbc.queryForObject("""
            INSERT INTO order_items (order_id, listing_id, seller_id, quantity, unit_price, line_total)
            VALUES (?, ?, ?, 1, 349.00, 349.00) RETURNING id
            """, UUID.class, orderId, listingId, sellerId);

        jdbc.update("""
            INSERT INTO shipments (order_id, seller_id, status, origin_pincode, destination_pincode,
                                    transit_days, planned_dispatch_date, dispatched_at, delivered_at)
            VALUES (?, ?, 'DELIVERED', '411001', ?, 4, CURRENT_DATE - 6, now() - interval '6 days', ?)
            """, orderId, sellerId, AllocationFixtures.LOCAL_PINCODE, java.sql.Timestamp.from(deliveredAt));

        jdbc.update("""
            INSERT INTO claim_windows (order_item_id, opens_at, closes_at) VALUES (?, ?, ?)
            """, orderItemId, java.sql.Timestamp.from(deliveredAt), java.sql.Timestamp.from(deliveredAt.plusSeconds(72 * 3600)));

        return orderItemId;
    }

    private UUID deliverCodItem(java.time.Instant deliveredAt) {
        UUID orderItemId = deliverItem(deliveredAt);
        UUID orderId = jdbc.queryForObject("SELECT order_id FROM order_items WHERE id = ?", UUID.class, orderItemId);
        jdbc.update("UPDATE orders SET is_cash_on_delivery = TRUE WHERE id = ?", orderId);
        return orderItemId;
    }

    private UUID shipmentIdFor(UUID orderItemId) {
        return jdbc.queryForObject("""
            SELECT sh.id FROM order_items oi JOIN shipments sh ON sh.order_id = oi.order_id AND sh.seller_id = oi.seller_id
             WHERE oi.id = ?
            """, UUID.class, orderItemId);
    }

    private void submitClaim(UUID orderItemId) {
        UUID shipmentId = shipmentIdFor(orderItemId);
        jdbc.update("""
            INSERT INTO claims (order_item_id, shipment_id, buyer_subject_id, type, status)
            VALUES (?, ?, 'payout-buyer@example.com', 'DEAD_ON_ARRIVAL', 'SUBMITTED')
            """, orderItemId, shipmentId);
    }

    private Money sellerPayableBalance() {
        return ledger.balance(accounts.sellerPayable(sellerId));
    }

    @Test
    void approvingAnAlreadyPaidCycle_isANoOp_doesNotCorruptStatus() {
        // Real bug, found while building Day 47's automatic cycle-build
        // job: markApproved() had no status guard and would blindly
        // demote an already-PAID cycle back to APPROVED, silently
        // defeating initiate()'s own idempotency check the next time
        // someone (a re-run job, a retried request, a double-clicking
        // ops person) called approve() on it again.
        UUID cycleId = approvedCycle("500.00");
        payouts.initiate(cycleId, "ops-1");
        assertThat(statusOf(cycleId)).isEqualTo(PayoutStatus.PAID.name());

        payouts.approve(cycleId, "ops-1");

        assertThat(statusOf(cycleId))
            .as("approve() must not move an already-PAID cycle backward")
            .isEqualTo(PayoutStatus.PAID.name());
    }

    private String statusOf(UUID cycleId) {
        return jdbc.queryForObject("SELECT status FROM payout_cycles WHERE id = ?", String.class, cycleId);
    }

    @Test
    void payoutInitiation_isIdempotent_underRetry() {
        UUID cycleId = approvedCycle("5000.00");

        payouts.initiate(cycleId, "ops-1");
        payouts.initiate(cycleId, "ops-1");

        assertThat(ledgerTxnRepo.countByReference(cycleId.toString())).isEqualTo(1L);
        assertThat(sellerPayableBalance()).isEqualTo(Money.ZERO);
    }

    @Test
    void concurrentPayoutInitiation_postsOnce() throws Exception {
        UUID cycleId = approvedCycle("5000.00");
        runInParallel(8, () -> { payouts.initiate(cycleId, "ops-1"); return null; });
        assertThat(ledgerTxnRepo.countByReference(cycleId.toString())).isEqualTo(1L);
    }

    @Test
    void payoutExceedingPayableBalance_isRejected() {
        UUID cycleId = approvedCycleWithAmountExceedingBalance();
        assertThatThrownBy(() -> payouts.initiate(cycleId, "ops-1"))
            .isInstanceOf(InsufficientPayableException.class);
    }

    @Test
    void failedPayout_reversesLedgerPosting_andRestoresPayable() {
        UUID cycleId = approvedCycle("5000.00");
        payoutGateway.failNextTransfer();

        assertThatThrownBy(() -> payouts.initiate(cycleId, "ops-1")).isInstanceOf(RuntimeException.class);

        // The post debited sellerPayable, then the transfer failed and
        // PayoutFailureHandler reversed it - balance is back to what
        // fundSellerPayable() originally set up.
        assertThat(sellerPayableBalance()).isEqualTo(Money.of("5000.00"));

        // Only the original PAYOUT post carries the cycle's own reference -
        // LedgerService.reverse()'s own reversal transaction is keyed by
        // the free-text failure reason, not the cycle id (confirmed by
        // reading reverse() itself, not assumed) - so this stays 1, not 2.
        assertThat(ledgerTxnRepo.countByReference(cycleId.toString())).isEqualTo(1L);

        BigDecimal globalSum = jdbc.queryForObject("SELECT SUM(amount) FROM ledger_entries", BigDecimal.class);
        assertThat(globalSum).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void orderInClaimsWindow_isExcludedFromCycle() {
        deliverItem(java.time.Instant.now().minusSeconds(24 * 3600)); // 24h ago - 72h window still open
        PayoutCycle cycle = payouts.buildCycle(sellerId, periodStart, periodEnd);
        assertThat(cycle.lineItems()).isEmpty();
    }

    @Test
    void itemWithOpenClaim_isExcludedFromCycle() {
        UUID orderItemId = deliverItem(java.time.Instant.now().minusSeconds(6 * 24 * 3600)); // window closed
        submitClaim(orderItemId);
        PayoutCycle cycle = payouts.buildCycle(sellerId, periodStart, periodEnd);
        assertThat(cycle.lineItems()).isEmpty();
    }

    @Test
    void codOrder_settlesOnCourierRemittance_notOnDelivery() {
        UUID orderItemId = deliverCodItem(java.time.Instant.now().minusSeconds(6 * 24 * 3600));
        assertThat(payouts.buildCycle(sellerId, periodStart, periodEnd).lineItems()).isEmpty();

        UUID shipmentId = shipmentIdFor(orderItemId);
        jdbc.update("INSERT INTO cod_remittances (shipment_id, amount) VALUES (?, 349.00)", shipmentId);

        assertThat(payouts.buildCycle(sellerId, periodStart, periodEnd).lineItems()).hasSize(1);
    }

    @Test
    void payoutStatement_lineItemsSumToNetAmount() {
        deliverItem(java.time.Instant.now().minusSeconds(6 * 24 * 3600));
        PayoutCycle cycle = payouts.buildCycle(sellerId, periodStart, periodEnd);

        Money sum = cycle.lineItems().stream().map(l -> l.net()).reduce(Money.ZERO, Money::plus);
        assertThat(sum).isEqualTo(cycle.netAmount());
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
