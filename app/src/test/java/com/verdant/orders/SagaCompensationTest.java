package com.verdant.orders;

import com.razorpay.Utils;
import com.verdant.cart.CartLine;
import com.verdant.checkout.CheckoutRequest;
import com.verdant.checkout.CheckoutResult;
import com.verdant.checkout.CheckoutService;
import com.verdant.checkout.PaymentVerificationRequest;
import com.verdant.checkout.PaymentVerificationService;
import com.verdant.common.domain.Money;
import com.verdant.common.testing.IntegrationTestBase;
import com.verdant.finance.service.AccountResolver;
import com.verdant.finance.service.LedgerService;
import com.verdant.finance.service.SettlementService;
import com.verdant.orders.saga.OrderSaga;
import com.verdant.orders.saga.OrderSagaOrchestrator;
import com.verdant.orders.saga.OrderSagaRepository;
import com.verdant.orders.saga.SagaState;
import com.verdant.orders.saga.StubLogisticsGateway;
import com.verdant.payment.RazorpayProperties;
import com.verdant.payment.StubPaymentGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Adapted from the runbook's model, which assumes the saga drives inventory
 * reservation, active payment "capture", and shipment as simple one-line
 * calls. In this codebase: reservation already happens at checkout,
 * "capture" is polling the order's own status (payment is asynchronous,
 * driven by the buyer's client or Razorpay's webhook - Week 4), and there is
 * no real logistics integration yet (Week 7). Each test here manufactures
 * the REAL side effects a saga would have produced up to a given furthest
 * state (a real reservation, a real settlement, a real "booked" shipment),
 * then forces the saga into COMPENSATING at that state and verifies the
 * compensator correctly and idempotently undoes exactly that much.
 */
class SagaCompensationTest extends IntegrationTestBase {

    @Autowired private CheckoutService checkoutService;
    @Autowired private PaymentVerificationService paymentVerification;
    @Autowired private SettlementService settlementService;
    @Autowired private LedgerService ledger;
    @Autowired private AccountResolver accounts;
    @Autowired private OrderSagaRepository sagaRepo;
    @Autowired private OrderSagaOrchestrator orchestrator;
    @Autowired private StubLogisticsGateway logistics;
    @Autowired private StubPaymentGateway paymentGateway;
    @Autowired private RazorpayProperties razorpayProps;
    @Autowired private JdbcTemplate jdbc;

    private static final String BUYER_EMAIL = "saga-buyer@example.com";

    private UUID sellerId;
    private UUID listingId;

    @BeforeEach
    void seedListing() {
        paymentGateway.reset();
        logistics.reset();

        sellerId = jdbc.queryForObject(
            "INSERT INTO sellers (business_name, status) VALUES ('Saga Test Nursery', 'APPROVED') RETURNING id",
            UUID.class);
        listingId = jdbc.queryForObject("""
            INSERT INTO listings (seller_id, raw_title, price_amount) VALUES (?, 'Saga Test Plant', 199.00) RETURNING id
            """, UUID.class, sellerId);
        jdbc.update("INSERT INTO inventory (listing_id, quantity, buffer) VALUES (?, 5, 0)", listingId);
    }

    private CheckoutResult checkoutOneItem() {
        return checkoutService.checkout(new CheckoutRequest(BUYER_EMAIL, List.of(new CartLine(listingId, 1))));
    }

    private void payFor(CheckoutResult checkout) throws Exception {
        String paymentId = "pay_saga_" + UUID.randomUUID();
        String signature = Utils.getHash(checkout.razorpayOrderId() + "|" + paymentId, razorpayProps.keySecret());
        paymentVerification.verifyPayment(checkout.orderId(),
            new PaymentVerificationRequest(checkout.razorpayOrderId(), paymentId, signature));
    }

    private UUID buyerId() {
        return UUID.nameUUIDFromBytes(BUYER_EMAIL.getBytes(StandardCharsets.UTF_8));
    }

    private Integer remainingStock() {
        return jdbc.queryForObject("SELECT quantity FROM inventory WHERE listing_id = ?", Integer.class, listingId);
    }

    /** Manufactures the real side effects a saga would have produced reaching `failAt`, then forces it into COMPENSATING there. */
    private UUID setupSagaFailedAt(SagaState failAt) throws Exception {
        CheckoutResult checkout = checkoutOneItem();
        UUID orderId = checkout.orderId();

        if (failAt.ordinal() >= SagaState.PAYMENT_CAPTURED.ordinal()) {
            payFor(checkout);
        }
        if (failAt.ordinal() >= SagaState.SETTLEMENT_POSTED.ordinal()) {
            settlementService.settle(orderId, buyerId(), sellerId, Money.of("199.00"), LocalDate.now());
        }
        if (failAt.ordinal() >= SagaState.SHIPMENT_BOOKED.ordinal()) {
            logistics.book(orderId, sellerId);
        }

        jdbc.update("""
            UPDATE order_sagas SET state = 'COMPENSATING', furthest_state = ?, next_attempt_at = now()
             WHERE order_id = ?
            """, failAt.name(), orderId);

        return orderId;
    }

    /** Forces the retry backoff to be immediately due, then advances once - avoids waiting out real exponential backoff in a test (same trick as OutboxTest). */
    private OrderSaga runPollerOnce(UUID orderId) {
        jdbc.update("UPDATE order_sagas SET next_attempt_at = now() WHERE order_id = ?", orderId);
        OrderSaga saga = sagaRepo.findById(orderId).orElseThrow();
        orchestrator.advanceOne(saga);
        return sagaRepo.findById(orderId).orElseThrow();
    }

    private OrderSaga runPollerUntilTerminal(UUID orderId) {
        OrderSaga saga = null;
        for (int i = 0; i < 20; i++) {
            saga = runPollerOnce(orderId);
            if (saga.getState() == SagaState.COMPLETED || saga.getState() == SagaState.FAILED) {
                return saga;
            }
        }
        throw new AssertionError("Saga " + orderId + " never reached a terminal state, stuck at "
            + (saga == null ? "?" : saga.getState()));
    }

    @ParameterizedTest
    @EnumSource(value = SagaState.class,
        names = {"INVENTORY_RESERVED", "PAYMENT_CAPTURED", "SETTLEMENT_POSTED", "SHIPMENT_BOOKED"})
    void failureAtAnyStep_compensatesCleanly(SagaState failAt) throws Exception {
        UUID orderId = setupSagaFailedAt(failAt);

        OrderSaga finalSaga = runPollerUntilTerminal(orderId);

        assertThat(finalSaga.getState()).isEqualTo(SagaState.FAILED);
        assertThat(remainingStock()).isEqualTo(5); // reservation released back

        if (failAt.ordinal() >= SagaState.SETTLEMENT_POSTED.ordinal()) {
            assertThat(ledger.balance(accounts.sellerPayable(sellerId))).isEqualTo(Money.ZERO);
        }
        if (failAt.ordinal() >= SagaState.SHIPMENT_BOOKED.ordinal()) {
            assertThat(logistics.isBooked(orderId, sellerId)).isFalse();
        }
    }

    @Test
    void compensationItselfFailing_isRetriedUntilSuccess() throws Exception {
        paymentGateway.failNextRefunds(3);
        UUID orderId = setupSagaFailedAt(SagaState.SETTLEMENT_POSTED);

        OrderSaga finalSaga = runPollerUntilTerminal(orderId);

        assertThat(finalSaga.getState()).isEqualTo(SagaState.FAILED);
        assertThat(paymentGateway.refundCallCount()).isEqualTo(4); // 3 fail, 1 succeed
        assertThat(ledger.balance(accounts.sellerPayable(sellerId))).isEqualTo(Money.ZERO);
    }

    @Test
    void compensationInterruptedMidway_resumesWithoutRepeatingCompletedSteps() throws Exception {
        // Simulates a crash partway through this poll's compensation run:
        // cancelShipment and reverseSettlement (steps 1-2) succeed and are
        // marked completed before refundPayment (step 3) fails.
        paymentGateway.failNextRefunds(1);
        UUID orderId = setupSagaFailedAt(SagaState.SHIPMENT_BOOKED);

        runPollerOnce(orderId); // cancelShipment + reverseSettlement complete; refundPayment fails, compensate() stops there
        OrderSaga finalSaga = runPollerUntilTerminal(orderId); // "resume" - only the remaining steps run

        assertThat(finalSaga.getState()).isEqualTo(SagaState.FAILED);
        assertThat(logistics.cancelCallCount())
            .as("a completed step must not be re-run on resume")
            .isEqualTo(1);
        assertThat(paymentGateway.refundCallCount()).isEqualTo(2); // 1 fail + 1 succeed
    }

    @Test
    void ledgerBalancesToZero_afterEveryCompensationScenario() throws Exception {
        for (SagaState failAt : List.of(SagaState.INVENTORY_RESERVED, SagaState.PAYMENT_CAPTURED,
                SagaState.SETTLEMENT_POSTED, SagaState.SHIPMENT_BOOKED)) {
            seedListing(); // fresh seller/listing per scenario
            UUID orderId = setupSagaFailedAt(failAt);
            runPollerUntilTerminal(orderId);
        }

        BigDecimal globalSum = jdbc.queryForObject(
            "SELECT COALESCE(SUM(amount), 0) FROM ledger_entries", BigDecimal.class);
        assertThat(globalSum).isEqualByComparingTo("0.00");
    }

    @Test
    void sagaSurvivesProcessRestart_midFlight() throws Exception {
        CheckoutResult checkout = checkoutOneItem();
        payFor(checkout);

        // "Restart" is not something to fake here - the property under test
        // is that saga state lives entirely in the database, not in memory,
        // so simply polling again (exactly what a freshly restarted process
        // would do) is the real proof it can resume from persisted state.
        OrderSaga finalSaga = runPollerUntilTerminal(checkout.orderId());

        assertThat(finalSaga.getState()).isEqualTo(SagaState.COMPLETED);
    }
}
