package com.verdant.logistics;

import com.razorpay.Utils;
import com.verdant.cart.CartLine;
import com.verdant.checkout.CheckoutRequest;
import com.verdant.checkout.CheckoutResult;
import com.verdant.checkout.CheckoutService;
import com.verdant.checkout.PaymentVerificationRequest;
import com.verdant.checkout.PaymentVerificationService;
import com.verdant.common.testing.AllocationFixtures;
import com.verdant.common.testing.IntegrationTestBase;
import com.verdant.logistics.service.DispatchOrchestrationJob;
import com.verdant.orders.saga.OrderSaga;
import com.verdant.orders.saga.OrderSagaOrchestrator;
import com.verdant.orders.saga.OrderSagaRepository;
import com.verdant.orders.saga.SagaState;
import com.verdant.payment.RazorpayProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The real gap DispatchOrchestrationJob closes: proves a REAL order,
 * carried all the way through checkout -> payment -> the live saga to a
 * real BOOKED shipment, actually gets picked up and either really booked
 * with the courier or really held on weather - not just that the pieces
 * work individually in isolation (already proven, ShipmentBookingTest /
 * DispatchGateTest / HoldLifecycleTest).
 */
class DispatchOrchestrationJobTest extends IntegrationTestBase {

    private static final String BUYER_EMAIL = "orchestration-buyer@example.com";

    @Autowired private CheckoutService checkoutService;
    @Autowired private PaymentVerificationService paymentVerification;
    @Autowired private OrderSagaOrchestrator orchestrator;
    @Autowired private OrderSagaRepository sagaRepo;
    @Autowired private RazorpayProperties razorpayProps;
    @Autowired private DispatchOrchestrationJob orchestrationJob;
    @Autowired private StubCourierGateway courierStub;
    @Autowired private CacheManager cacheManager;
    @Autowired private JdbcTemplate jdbc;

    private UUID sellerId;
    private UUID listingId;

    @BeforeEach
    void seed() {
        courierStub.reset();
        AllocationFixtures.seedLocalPincodeZone(jdbc);
        sellerId = AllocationFixtures.seedApprovedSeller(jdbc, "Orchestration Test Nursery");
        UUID canonicalProductId = AllocationFixtures.seedCanonicalProduct(jdbc, "Fixture Widget Orchestration");
        listingId = AllocationFixtures.seedListing(jdbc, sellerId, canonicalProductId, "Fixture Widget Orchestration", "199.00", 20);
        setFlag("shiprocket_booking", true, false);
        setFlag("weather_gating", true, false);
    }

    private void setFlag(String name, boolean enabled, boolean shadow) {
        jdbc.update("UPDATE feature_flags SET enabled = ?, shadow_mode = ? WHERE name = ?", enabled, shadow, name);
        var cache = cacheManager.getCache("flags");
        if (cache != null) cache.evict(name);
    }

    private UUID checkoutToBookedShipment() throws Exception {
        CheckoutResult checkout = checkoutService.checkout(
            new CheckoutRequest(BUYER_EMAIL, List.of(new CartLine(listingId, 1)), AllocationFixtures.LOCAL_PINCODE));

        String paymentId = "pay_orch_" + UUID.randomUUID();
        String signature = Utils.getHash(checkout.razorpayOrderId() + "|" + paymentId, razorpayProps.keySecret());
        paymentVerification.verifyPayment(checkout.orderId(),
            new PaymentVerificationRequest(checkout.razorpayOrderId(), paymentId, signature));

        for (int i = 0; i < 10; i++) {
            jdbc.update("UPDATE order_sagas SET next_attempt_at = now() WHERE order_id = ?", checkout.orderId());
            OrderSaga saga = sagaRepo.findById(checkout.orderId()).orElseThrow();
            orchestrator.advanceOne(saga);
            saga = sagaRepo.findById(checkout.orderId()).orElseThrow();
            if (saga.getState() == SagaState.SHIPMENT_BOOKED || saga.getState() == SagaState.COMPLETED) {
                break;
            }
        }
        return jdbc.queryForObject(
            "SELECT id FROM shipments WHERE order_id = ? AND seller_id = ?", UUID.class, checkout.orderId(), sellerId);
    }

    private String statusOf(UUID shipmentId) {
        return jdbc.queryForObject("SELECT status FROM shipments WHERE id = ?", String.class, shipmentId);
    }

    private void seedForecast(String pincode, double maxTempC, double rainfallMm) {
        jdbc.update("""
            INSERT INTO weather_forecasts (pincode, forecast_date, max_temp_c, min_temp_c, rainfall_mm, fetched_at)
            VALUES (?, ?, ?, ?, ?, now())
            ON CONFLICT (pincode, forecast_date) DO UPDATE SET max_temp_c = EXCLUDED.max_temp_c,
                rainfall_mm = EXCLUDED.rainfall_mm, fetched_at = now()
            """, pincode, LocalDate.now(), maxTempC, maxTempC - 8, rainfallMm);
    }

    @Test
    void realOrder_reachingABookedShipment_getsReallyBookedWithTheCourier() throws Exception {
        seedForecast(AllocationFixtures.LOCAL_PINCODE, 28.0, 0); // safe weather
        UUID shipmentId = checkoutToBookedShipment();
        assertThat(statusOf(shipmentId)).isEqualTo("BOOKED");
        assertThat(courierStub.createCalls()).isZero(); // nothing has run orchestration yet

        orchestrationJob.orchestrate();

        assertThat(courierStub.createCalls()).isEqualTo(1);
        Long hasAwb = jdbc.queryForObject(
            "SELECT count(*) FROM shipments WHERE id = ? AND awb_number IS NOT NULL", Long.class, shipmentId);
        assertThat(hasAwb).isEqualTo(1L);
    }

    @Test
    void realOrder_withUnsafeWeather_isHeldNotBooked() throws Exception {
        // AllocationFixtures.seedCanonicalProduct defaults to HIGH heat
        // tolerance (max safe 42.0C) - 44.0 is clearly above it, not just
        // at the boundary.
        seedForecast(AllocationFixtures.LOCAL_PINCODE, 44.0, 0); // hot
        UUID shipmentId = checkoutToBookedShipment();

        orchestrationJob.orchestrate();

        assertThat(statusOf(shipmentId)).isEqualTo("HELD");
        assertThat(courierStub.createCalls()).isZero(); // never even reached the courier
    }

    @Test
    void orchestration_neverBreaksTheSellerPackDispatchFlow_whenBookingFlagIsOff() throws Exception {
        setFlag("shiprocket_booking", false, false);
        seedForecast(AllocationFixtures.LOCAL_PINCODE, 28.0, 0);
        UUID shipmentId = checkoutToBookedShipment();

        orchestrationJob.orchestrate();

        // The real bug this test exists to catch: orchestration must never
        // leave a shipment un-packable by changing its status away from
        // 'BOOKED' when there's no real courier to hand off to.
        assertThat(statusOf(shipmentId)).isEqualTo("BOOKED");
        int packed = jdbc.update(
            "UPDATE shipments SET status = 'PACKED', packed_at = now() WHERE id = ? AND status = 'BOOKED'", shipmentId);
        assertThat(packed).isEqualTo(1);
    }
}
