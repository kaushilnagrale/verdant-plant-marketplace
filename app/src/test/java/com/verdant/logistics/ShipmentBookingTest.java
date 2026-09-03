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
import com.verdant.logistics.service.ShipmentBookingService;
import com.verdant.logistics.service.ShipmentEventRepository;
import com.verdant.logistics.service.ShipmentStatusProjector;
import com.verdant.logistics.web.TrackingEvent;
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

class ShipmentBookingTest extends IntegrationTestBase {

    private static final String BUYER_EMAIL = "booking-buyer@example.com";

    @Autowired private CheckoutService checkoutService;
    @Autowired private PaymentVerificationService paymentVerification;
    @Autowired private OrderSagaOrchestrator orchestrator;
    @Autowired private OrderSagaRepository sagaRepo;
    @Autowired private RazorpayProperties razorpayProps;
    @Autowired private ShipmentBookingService booking;
    @Autowired private StubCourierGateway courierStub;
    @Autowired private ShipmentEventRepository shipmentEvents;
    @Autowired private ShipmentStatusProjector statusProjector;
    @Autowired private CacheManager cacheManager;
    @Autowired private JdbcTemplate jdbc;

    private UUID sellerId;
    private UUID listingId;

    @BeforeEach
    void seed() {
        courierStub.reset();
        AllocationFixtures.seedLocalPincodeZone(jdbc);
        sellerId = AllocationFixtures.seedApprovedSeller(jdbc, "Booking Test Nursery");
        UUID canonicalProductId = AllocationFixtures.seedCanonicalProduct(jdbc, "Fixture Widget Booking");
        listingId = AllocationFixtures.seedListing(jdbc, sellerId, canonicalProductId, "Fixture Widget Booking", "199.00", 20);
        setFlag("shiprocket_booking", true, false);
    }

    /** Real checkout -> real payment -> the live saga's own scheduler-driven advance, until a real (our own sense) BOOKED shipment row exists. */
    private UUID createShipment() throws Exception {
        CheckoutResult checkout = checkoutService.checkout(
            new CheckoutRequest(BUYER_EMAIL, List.of(new CartLine(listingId, 1)), AllocationFixtures.LOCAL_PINCODE));

        String paymentId = "pay_booking_" + UUID.randomUUID();
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

    private UUID orderOf(UUID shipmentId) {
        return jdbc.queryForObject("SELECT order_id FROM shipments WHERE id = ?", UUID.class, shipmentId);
    }

    private String awbOf(UUID shipmentId) {
        return jdbc.queryForObject("SELECT awb_number FROM shipments WHERE id = ?", String.class, shipmentId);
    }

    private String statusOf(UUID shipmentId) {
        return jdbc.queryForObject("SELECT status FROM shipments WHERE id = ?", String.class, shipmentId);
    }

    private long countShipmentsForOrder(UUID orderId) {
        return jdbc.queryForObject("SELECT count(*) FROM shipments WHERE order_id = ?", Long.class, orderId);
    }

    /** DB write + cache eviction together - the "flags" cache (Day 37, 30s TTL) would otherwise serve a stale value from an earlier test in the same run. */
    private void setFlag(String name, boolean enabled, boolean shadow) {
        jdbc.update("UPDATE feature_flags SET enabled = ?, shadow_mode = ? WHERE name = ?", enabled, shadow, name);
        var cache = cacheManager.getCache("flags");
        if (cache != null) cache.evict(name);
    }

    private void postTracking(UUID shipmentId, String status, String providerEventId, Instant occurredAt) {
        String awb = awbOf(shipmentId);
        try {
            shipmentEvents.insert(shipmentId, new TrackingEvent(awb, providerEventId, status, null, null, occurredAt));
        } catch (org.springframework.dao.DataIntegrityViolationException duplicate) {
            return; // same UNIQUE(shipment_id, provider_event_id) dedup the real webhook controller relies on
        }
        statusProjector.project(shipmentId);
    }

    @Test
    void bookingRetriedAfterTimeout_doesNotCreateDuplicateShipment() throws Exception {
        UUID id = createShipment();
        courierStub.timeoutOnNextCall();

        try {
            booking.book(id);
        } catch (Exception ignored) { }
        booking.book(id);

        assertThat(awbOf(id)).isNotNull();
        assertThat(countShipmentsForOrder(orderOf(id))).isEqualTo(1);
    }

    @Test
    void concurrentBookingCalls_produceOneAwb() throws Exception {
        UUID id = createShipment();
        runInParallel(8, () -> { booking.book(id); return null; });
        assertThat(courierStub.distinctAwbsIssued()).isEqualTo(1);
    }

    @Test
    void trackingEventsOutOfOrder_produceCorrectFinalStatus() throws Exception {
        UUID id = createShipment();
        booking.book(id);

        postTracking(id, "DELIVERED", "evt_3", Instant.now().minusSeconds(3600));
        postTracking(id, "IN_TRANSIT", "evt_1", Instant.now().minusSeconds(36000)); // late
        postTracking(id, "OUT_FOR_DELIVERY", "evt_2", Instant.now().minusSeconds(14400));

        assertThat(statusOf(id)).isEqualTo("DELIVERED");
    }

    @Test
    void duplicateTrackingEvent_isStoredOnce() throws Exception {
        UUID id = createShipment();
        booking.book(id);

        postTracking(id, "IN_TRANSIT", "evt_1", Instant.now().minusSeconds(7200));
        postTracking(id, "IN_TRANSIT", "evt_1", Instant.now().minusSeconds(7200));

        assertThat(shipmentEvents.countByShipmentId(id)).isEqualTo(1);
    }

    @Test
    void bookingDisabledByFlag_marksReadyForManualDispatch() throws Exception {
        setFlag("shiprocket_booking", false, false);
        UUID id = createShipment();

        booking.book(id);

        assertThat(statusOf(id)).isEqualTo("READY");
        assertThat(courierStub.createCalls()).isZero();
    }

    private <T> List<T> runInParallel(int n, Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return task.call();
            }));
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
