package com.verdant.seller;

import com.razorpay.Utils;
import com.verdant.cart.CartLine;
import com.verdant.checkout.CheckoutRequest;
import com.verdant.checkout.CheckoutResult;
import com.verdant.checkout.CheckoutService;
import com.verdant.checkout.PaymentVerificationRequest;
import com.verdant.checkout.PaymentVerificationService;
import com.verdant.common.testing.AllocationFixtures;
import com.verdant.common.testing.IntegrationTestBase;
import com.verdant.orders.saga.OrderSaga;
import com.verdant.orders.saga.OrderSagaOrchestrator;
import com.verdant.orders.saga.OrderSagaRepository;
import com.verdant.orders.saga.SagaState;
import com.verdant.payment.RazorpayProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class SellerOrderControllerTest extends IntegrationTestBase {

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private CheckoutService checkoutService;
    @Autowired private PaymentVerificationService paymentVerification;
    @Autowired private OrderSagaRepository sagaRepo;
    @Autowired private OrderSagaOrchestrator orchestrator;
    @Autowired private RazorpayProperties razorpayProps;

    private UUID sellerId;
    private String subjectId;
    private UUID listingId;

    @BeforeEach
    void seed() {
        AllocationFixtures.seedLocalPincodeZone(jdbc);
        sellerId = AllocationFixtures.seedApprovedSeller(jdbc, "Seller Order Test Nursery");
        subjectId = "sub-seller-order-" + UUID.randomUUID();
        jdbc.update("INSERT INTO seller_users (seller_id, subject_id, role) VALUES (?, ?, 'OWNER')", sellerId, subjectId);
        UUID canonicalProductId = AllocationFixtures.seedCanonicalProduct(jdbc, "Fixture Widget Dispatch");
        listingId = AllocationFixtures.seedListing(jdbc, sellerId, canonicalProductId, "Fixture Widget Dispatch", "150.00", 5);
    }

    /** Real checkout, real payment verification, then drives the live saga (same trick as SagaCompensationTest) until its shipment is genuinely booked. */
    private UUID checkoutAndReachShipmentBooked() throws Exception {
        CheckoutResult checkout = checkoutService.checkout(
            new CheckoutRequest("dispatch-buyer@example.com", List.of(new CartLine(listingId, 1)),
                AllocationFixtures.LOCAL_PINCODE));

        String paymentId = "pay_dispatch_" + UUID.randomUUID();
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
        return checkout.orderId();
    }

    /** The seller app's own real sign-in call (lib/api.ts's fetchMySellerId()) - resolves the caller's own internal seller id from their JWT subject. */
    @Test
    void me_returnsTheCallersOwnSellerId() throws Exception {
        mockMvc.perform(get("/v1/seller/orders/me").with(jwt().jwt(j -> j.subject(subjectId))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sellerId").value(sellerId.toString()));
    }

    @Test
    void myOrders_showsThisSellersOwnOrder() throws Exception {
        UUID orderId = checkoutAndReachShipmentBooked();

        mockMvc.perform(get("/v1/seller/orders").with(jwt().jwt(j -> j.subject(subjectId))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.orderId == '" + orderId + "')]").exists());
    }

    @Test
    void packThenDispatch_transitionsShipmentCorrectly() throws Exception {
        UUID orderId = checkoutAndReachShipmentBooked();

        mockMvc.perform(post("/v1/seller/orders/" + orderId + "/pack").with(jwt().jwt(j -> j.subject(subjectId))))
            .andExpect(status().isOk());
        assertThat(shipmentStatus(orderId)).isEqualTo("PACKED");

        mockMvc.perform(post("/v1/seller/orders/" + orderId + "/dispatch").with(jwt().jwt(j -> j.subject(subjectId))))
            .andExpect(status().isOk());
        assertThat(shipmentStatus(orderId)).isEqualTo("DISPATCHED");
    }

    /** Real quality-scoring wiring (Week 10) - planned_dispatch_date (StubLogisticsGateway) is always at least tomorrow (CURRENT_DATE + ceil(handling_time_hours/24), and every listing here defaults to 24h), so dispatching immediately after booking is always genuinely on time. */
    @Test
    void dispatch_withinHandlingTime_recordsOnTimeQualityEvent() throws Exception {
        UUID orderId = checkoutAndReachShipmentBooked();
        mockMvc.perform(post("/v1/seller/orders/" + orderId + "/pack").with(jwt().jwt(j -> j.subject(subjectId))))
            .andExpect(status().isOk());

        mockMvc.perform(post("/v1/seller/orders/" + orderId + "/dispatch").with(jwt().jwt(j -> j.subject(subjectId))))
            .andExpect(status().isOk());

        List<String> eventTypes = jdbc.queryForList(
            "SELECT event_type FROM seller_quality_events WHERE seller_id = ? AND order_id = ?",
            String.class, sellerId, orderId);
        assertThat(eventTypes).containsExactly("DISPATCH_ON_TIME");
    }

    @Test
    void dispatch_pastHandlingTime_recordsLateQualityEvent() throws Exception {
        UUID orderId = checkoutAndReachShipmentBooked();
        mockMvc.perform(post("/v1/seller/orders/" + orderId + "/pack").with(jwt().jwt(j -> j.subject(subjectId))))
            .andExpect(status().isOk());
        // Back-date the deadline itself, same technique OrderExpiryServiceTest
        // already established for simulating "time has passed" without a
        // real sleep - dispatching "now" against a deadline of yesterday is
        // unambiguously late, regardless of when this test happens to run.
        jdbc.update("UPDATE shipments SET planned_dispatch_date = CURRENT_DATE - 1 WHERE order_id = ? AND seller_id = ?",
            orderId, sellerId);

        mockMvc.perform(post("/v1/seller/orders/" + orderId + "/dispatch").with(jwt().jwt(j -> j.subject(subjectId))))
            .andExpect(status().isOk());

        List<String> eventTypes = jdbc.queryForList(
            "SELECT event_type FROM seller_quality_events WHERE seller_id = ? AND order_id = ?",
            String.class, sellerId, orderId);
        assertThat(eventTypes).containsExactly("DISPATCH_LATE");
    }

    @Test
    void dispatch_withoutPackingFirst_isRejected() throws Exception {
        UUID orderId = checkoutAndReachShipmentBooked();

        mockMvc.perform(post("/v1/seller/orders/" + orderId + "/dispatch").with(jwt().jwt(j -> j.subject(subjectId))))
            .andExpect(status().isConflict());
        assertThat(shipmentStatus(orderId)).isEqualTo("BOOKED");
    }

    /**
     * Day 56's own "dispatchActionRetried_afterNetworkFailure_appliesOnce" -
     * the seller app's own offline queue (queue.ts) retries a dispatch tap
     * whose response never arrived, and this is the real guarantee behind
     * that being safe: markDispatched() is a guarded state transition
     * (WHERE status = 'PACKED', same as every other real atomic-guarded
     * UPDATE in this project), so a genuine retry after the first call
     * already succeeded finds nothing left to do and reports it as a 409 -
     * exactly the "already applied" signal the frontend's own
     * isPermanent() check (a 4xx) already treats as "drop it, don't keep
     * retrying" - never a second real dispatch.
     */
    @Test
    void dispatchActionRetried_afterFirstSucceeded_appliesOnce_notTwice() throws Exception {
        UUID orderId = checkoutAndReachShipmentBooked();
        mockMvc.perform(post("/v1/seller/orders/" + orderId + "/pack").with(jwt().jwt(j -> j.subject(subjectId))))
            .andExpect(status().isOk());

        mockMvc.perform(post("/v1/seller/orders/" + orderId + "/dispatch").with(jwt().jwt(j -> j.subject(subjectId))))
            .andExpect(status().isOk());
        // The retry - as if the first response never reached the seller's phone.
        mockMvc.perform(post("/v1/seller/orders/" + orderId + "/dispatch").with(jwt().jwt(j -> j.subject(subjectId))))
            .andExpect(status().isConflict());

        assertThat(shipmentStatus(orderId)).isEqualTo("DISPATCHED");
        Integer dispatchEvents = jdbc.queryForObject("""
            SELECT count(*) FROM seller_quality_events WHERE seller_id = ? AND order_id = ?
                AND event_type IN ('DISPATCH_ON_TIME','DISPATCH_LATE')
            """, Integer.class, sellerId, orderId);
        assertThat(dispatchEvents)
            .as("the retry never re-ran markDispatched()'s own real quality-event recording a second time")
            .isEqualTo(1);
    }

    /** The other real half of vacationMode_stopsNewOrderAllocation_butKeepsExistingFulfillable (SellerAccountControllerTest covers the allocation-stops half) - an order already in this seller's own queue before they went on vacation is untouched, since pack/dispatch never re-run allocation at all. */
    @Test
    void vacationMode_doesNotBlockPackingOrDispatchingAnExistingOrder() throws Exception {
        UUID orderId = checkoutAndReachShipmentBooked();
        jdbc.update("UPDATE sellers SET on_vacation = true WHERE id = ?", sellerId);

        mockMvc.perform(post("/v1/seller/orders/" + orderId + "/pack").with(jwt().jwt(j -> j.subject(subjectId))))
            .andExpect(status().isOk());
        mockMvc.perform(post("/v1/seller/orders/" + orderId + "/dispatch").with(jwt().jwt(j -> j.subject(subjectId))))
            .andExpect(status().isOk());

        assertThat(shipmentStatus(orderId)).isEqualTo("DISPATCHED");
    }

    private String shipmentStatus(UUID orderId) {
        return jdbc.queryForObject(
            "SELECT status FROM shipments WHERE order_id = ? AND seller_id = ?", String.class, orderId, sellerId);
    }
}
