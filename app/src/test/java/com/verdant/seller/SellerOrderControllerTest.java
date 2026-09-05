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

    private String shipmentStatus(UUID orderId) {
        return jdbc.queryForObject(
            "SELECT status FROM shipments WHERE order_id = ? AND seller_id = ?", String.class, orderId, sellerId);
    }
}
