package com.verdant.finance;

import com.verdant.cart.CartLine;
import com.verdant.checkout.CheckoutRequest;
import com.verdant.checkout.CheckoutResult;
import com.verdant.checkout.CheckoutService;
import com.verdant.checkout.PaymentVerificationRequest;
import com.verdant.checkout.PaymentVerificationService;
import com.verdant.common.alerting.Alerting;
import com.verdant.common.domain.Money;
import com.verdant.common.testing.AllocationFixtures;
import com.verdant.common.testing.IntegrationTestBase;
import com.verdant.finance.domain.ReconciliationRun;
import com.verdant.finance.domain.ReconciliationStatus;
import com.verdant.finance.service.PaymentRecoveryService;
import com.verdant.finance.service.ReconciliationJob;
import com.verdant.finance.service.ReconciliationRunRepository;
import com.verdant.payment.ProviderPayment;
import com.verdant.payment.RazorpayProperties;
import com.verdant.payment.StubPaymentGateway;
import com.razorpay.Utils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * reconcile() is inherently whole-database and date-scoped, unlike almost
 * everything else this project tests - it is the first feature to query
 * "every order paid on date X", not one specific order by its own id. Real
 * wall-clock "today" is shared by every test file that ever drives a real
 * checkout+payment (PaymentVerificationServiceTest, OrderExpiryServiceTest,
 * etc. never needed to care, since nothing before this queried across
 * orders that way) - so every test here backdates paid_at to its own
 * synthetic date and reconciles THAT date, never real "today". Same
 * backdating technique OrderExpiryServiceTest already uses, applied here
 * to guarantee isolation rather than to simulate an expired window.
 *
 * A fixed hardcoded date per test was tried first and genuinely failed:
 * IntegrationTestBase's Postgres container is withReuse(true), meaning it
 * survives across SEPARATE Gradle invocations, not just within one run - a
 * fixed date collides with that same test's own leftover data from an
 * earlier run the moment the suite runs twice against the same container
 * (caught for real, not hypothetically, rerunning this file after an
 * earlier isolated run). Cleaning up afterward was tried next and
 * abandoned too: a real checkout genuinely rides the live saga
 * (OrderSagaOrchestrator polls on a fixedDelay, unlike the cron jobs,
 * so it isn't disabled for tests) and can spawn shipments/dispatch_holds
 * mid-test - an exhaustive FK-safe cleanup chasing every table a
 * completed order might touch is exactly the kind of fragile, ever-growing
 * cleanup no other test in this project attempts. Salting the date from
 * System.nanoTime() - different on every JVM invocation - makes every run
 * land on its own untouched slice of the calendar instead, so there is
 * nothing to collide with, ever, ruling the whole problem out rather than
 * chasing its symptoms.
 */
class ReconciliationTest extends IntegrationTestBase {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    // Distinct per JVM run (test executions on the same reused container
    // minutes or days apart get wildly different nanoTime() values), wide
    // enough that a 0-6 per-test offset never wraps into the next run's slot.
    private static final long RUN_BASE_EPOCH_DAY = 40_000 + Math.floorMod(System.nanoTime(), 900_000);

    private static LocalDate testDate(int offset) {
        return LocalDate.ofEpochDay(RUN_BASE_EPOCH_DAY + offset);
    }

    @Autowired private CheckoutService checkoutService;
    @Autowired private PaymentVerificationService verification;
    @Autowired private ReconciliationJob reconciliationJob;
    @Autowired private ReconciliationRunRepository runs;
    @Autowired private PaymentRecoveryService recovery;
    @Autowired private StubPaymentGateway paymentGateway;
    @Autowired private RazorpayProperties razorpayProps;
    @Autowired private Alerting alerting;
    @Autowired private JdbcTemplate jdbc;

    private UUID listingId;

    @BeforeEach
    void seed() {
        paymentGateway.reset();
        alerting.clear();
        AllocationFixtures.seedLocalPincodeZone(jdbc);
        UUID sellerId = AllocationFixtures.seedApprovedSeller(jdbc, "Reconciliation Test Nursery " + UUID.randomUUID());
        UUID canonicalProductId = AllocationFixtures.seedCanonicalProduct(jdbc, "Fixture Widget Reconciliation " + UUID.randomUUID());
        listingId = AllocationFixtures.seedListing(jdbc, sellerId, canonicalProductId, "Fixture Widget Reconciliation", "199.00", 5);
    }

    private CheckoutResult checkout() {
        return checkoutService.checkout(
            new CheckoutRequest("buyer@example.com", List.of(new CartLine(listingId, 1)),
                AllocationFixtures.LOCAL_PINCODE));
    }

    /** Drives a REAL payment through the real signature-verified path, so the resulting order is genuinely PAID with a real razorpay_payment_id - not a hand-rolled row. Backdates paid_at to the test's own isolated date afterward. */
    private String payFor(CheckoutResult checkout, LocalDate date) throws Exception {
        String paymentId = "pay_test_" + UUID.randomUUID();
        String signature = Utils.getHash(checkout.razorpayOrderId() + "|" + paymentId, razorpayProps.keySecret());
        verification.verifyPayment(checkout.orderId(),
            new PaymentVerificationRequest(checkout.razorpayOrderId(), paymentId, signature));
        backdatePaidAt(checkout.orderId(), date);
        return paymentId;
    }

    private void backdatePaidAt(UUID orderId, LocalDate date) {
        jdbc.update("UPDATE orders SET paid_at = ? WHERE id = ?",
            Timestamp.from(date.atTime(10, 0).atZone(IST).toInstant()), orderId);
    }

    private BigDecimal totalAmountOf(UUID orderId) {
        return jdbc.queryForObject("SELECT total_amount FROM orders WHERE id = ?", BigDecimal.class, orderId);
    }

    private String statusOf(UUID orderId) {
        return jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId);
    }

    @Test
    void paymentAtProviderButNotInternal_isFlagged_andReplayed() {
        LocalDate date = testDate(1);
        // The real webhook/client-callback path never fired - Razorpay
        // captured it, but our order is still sitting at CREATED (no
        // paid_at to backdate - nothing to reconcile against internally
        // until the replay below sets it).
        CheckoutResult checkout = checkout();
        String paymentId = "pay_test_" + UUID.randomUUID();
        BigDecimal amount = totalAmountOf(checkout.orderId());
        paymentGateway.seedCapturedPayments(date, List.of(
            new ProviderPayment(paymentId, checkout.razorpayOrderId(), Money.of(amount), "captured", Instant.now())));

        reconciliationJob.reconcile(date);

        int breakCount = jdbc.queryForObject(
            "SELECT count(*) FROM reconciliation_breaks WHERE break_type = 'MISSING_INTERNAL' AND provider_ref = ?",
            Integer.class, paymentId);
        assertThat(breakCount).isEqualTo(1);

        // Self-healing: the missed payment was replayed through the real
        // idempotent path, so the order is now genuinely PAID.
        assertThat(statusOf(checkout.orderId())).isEqualTo("PAID");
        String recordedPaymentId = jdbc.queryForObject(
            "SELECT razorpay_payment_id FROM orders WHERE id = ?", String.class, checkout.orderId());
        assertThat(recordedPaymentId).isEqualTo(paymentId);
    }

    @Test
    void amountMismatch_isFlaggedWithBothAmounts() throws Exception {
        LocalDate date = testDate(2);
        CheckoutResult checkout = checkout();
        String paymentId = payFor(checkout, date);
        BigDecimal internalAmount = totalAmountOf(checkout.orderId());
        BigDecimal providerAmount = internalAmount.add(BigDecimal.valueOf(50)); // deliberately wrong

        paymentGateway.seedCapturedPayments(date, List.of(
            new ProviderPayment(paymentId, checkout.razorpayOrderId(), Money.of(providerAmount), "captured", Instant.now())));

        reconciliationJob.reconcile(date);

        var row = jdbc.queryForMap(
            "SELECT provider_amount, internal_amount FROM reconciliation_breaks WHERE break_type = 'AMOUNT_MISMATCH' AND provider_ref = ?",
            paymentId);
        assertThat(((BigDecimal) row.get("provider_amount")).compareTo(Money.of(providerAmount).amount())).isZero();
        assertThat(((BigDecimal) row.get("internal_amount")).compareTo(Money.of(internalAmount).amount())).isZero();
    }

    @Test
    void internalPaymentNotAtProvider_isFlaggedAsMissingProvider() throws Exception {
        LocalDate date = testDate(3);
        CheckoutResult checkout = checkout();
        String paymentId = payFor(checkout, date);
        // Razorpay's captured list for this date simply doesn't include it - no seedCapturedPayments call at all.

        reconciliationJob.reconcile(date);

        int breakCount = jdbc.queryForObject(
            "SELECT count(*) FROM reconciliation_breaks WHERE break_type = 'MISSING_PROVIDER' AND provider_ref = ? AND order_id = ?",
            Integer.class, paymentId, checkout.orderId());
        assertThat(breakCount).isEqualTo(1);
    }

    @Test
    void cleanDay_producesZeroBreaks_andCleanStatus() throws Exception {
        LocalDate date = testDate(4);
        CheckoutResult checkout = checkout();
        String paymentId = payFor(checkout, date);
        BigDecimal amount = totalAmountOf(checkout.orderId());
        paymentGateway.seedCapturedPayments(date, List.of(
            new ProviderPayment(paymentId, checkout.razorpayOrderId(), Money.of(amount), "captured", Instant.now())));

        reconciliationJob.reconcile(date);

        ReconciliationRun run = runs.findByDate(date);
        assertThat(run.status()).isEqualTo(ReconciliationStatus.CLEAN);
        assertThat(run.breaksFound()).isZero();
        assertThat(run.matched()).isGreaterThanOrEqualTo(1);
        assertThat(alerting.captured()).noneMatch(m -> m.contains(date.toString()));
    }

    @Test
    void reconciliationRerun_forSameDate_isIdempotent() throws Exception {
        LocalDate date = testDate(5);
        CheckoutResult checkout = checkout();
        String paymentId = payFor(checkout, date);
        BigDecimal amount = totalAmountOf(checkout.orderId());
        paymentGateway.seedCapturedPayments(date, List.of(
            new ProviderPayment(paymentId, checkout.razorpayOrderId(), Money.of(amount), "captured", Instant.now())));

        reconciliationJob.reconcile(date);
        ReconciliationRun first = runs.findByDate(date);

        reconciliationJob.reconcile(date);
        ReconciliationRun second = runs.findByDate(date);

        // Same row (run_date is UNIQUE), same clean outcome - not a second,
        // conflicting run and not doubled break rows.
        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.status()).isEqualTo(ReconciliationStatus.CLEAN);
        assertThat(second.matched()).isEqualTo(first.matched());

        Integer totalBreaksForRun = jdbc.queryForObject(
            "SELECT count(*) FROM reconciliation_breaks WHERE run_id = ?", Integer.class, first.id());
        assertThat(totalBreaksForRun).isZero();
    }

    @Test
    void replayedPayment_doesNotDoubleSettle() {
        CheckoutResult checkout = checkout();
        String paymentId = "pay_test_" + UUID.randomUUID();
        BigDecimal amount = totalAmountOf(checkout.orderId());
        ProviderPayment payment = new ProviderPayment(paymentId, checkout.razorpayOrderId(), Money.of(amount), "captured", Instant.now());

        boolean firstReplay = recovery.replayPayment(payment);
        Instant paidAtAfterFirst = jdbc.queryForObject(
            "SELECT paid_at FROM orders WHERE id = ?", Instant.class, checkout.orderId());

        // Same payment reported again - the real webhook retrying, or a
        // second reconciliation run finding it again before the first
        // replay's effect was visible to it. Must be a safe no-op.
        boolean secondReplay = recovery.replayPayment(payment);
        Instant paidAtAfterSecond = jdbc.queryForObject(
            "SELECT paid_at FROM orders WHERE id = ?", Instant.class, checkout.orderId());

        assertThat(firstReplay).isTrue();
        assertThat(secondReplay).isFalse();
        assertThat(paidAtAfterSecond).isEqualTo(paidAtAfterFirst);
    }

    @Test
    void statusMismatch_isFlaggedWhenInternalStatusDisagreesWithProvider() throws Exception {
        // Simulates a data state the app's own atomic update (status and
        // razorpay_payment_id always flip together, PaymentVerificationService)
        // cannot currently produce on its own - a real defense-in-depth
        // check, proven directly rather than left untested just because
        // no live code path reaches it today (same reasoning as
        // ListingController's now-unreachable-for-RLS ownership check).
        LocalDate date = testDate(6);
        CheckoutResult checkout = checkout();
        String paymentId = payFor(checkout, date);
        jdbc.update("UPDATE orders SET status = 'CANCELLED' WHERE id = ?", checkout.orderId());
        BigDecimal amount = totalAmountOf(checkout.orderId());

        paymentGateway.seedCapturedPayments(date, List.of(
            new ProviderPayment(paymentId, checkout.razorpayOrderId(), Money.of(amount), "captured", Instant.now())));

        reconciliationJob.reconcile(date);

        int breakCount = jdbc.queryForObject(
            "SELECT count(*) FROM reconciliation_breaks WHERE break_type = 'STATUS_MISMATCH' AND provider_ref = ?",
            Integer.class, paymentId);
        assertThat(breakCount).isEqualTo(1);
    }
}
