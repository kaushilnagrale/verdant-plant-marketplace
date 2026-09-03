package com.verdant.checkout;

import com.razorpay.Utils;
import com.verdant.cart.CartLine;
import com.verdant.common.testing.AllocationFixtures;
import com.verdant.common.testing.IntegrationTestBase;
import com.verdant.payment.RazorpayProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Razorpay's own servers call this endpoint directly - there is no buyer
 * JWT involved, the signature over the raw body IS the authentication.
 */
@AutoConfigureMockMvc
class RazorpayWebhookControllerTest extends IntegrationTestBase {

    @Autowired private CheckoutService checkoutService;
    @Autowired private RazorpayProperties razorpayProps;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mockMvc;

    private UUID listingId;

    @BeforeEach
    void seedListing() {
        AllocationFixtures.seedLocalPincodeZone(jdbc);
        UUID sellerId = AllocationFixtures.seedApprovedSeller(jdbc, "Webhook Test Nursery");
        UUID canonicalProductId = AllocationFixtures.seedCanonicalProduct(jdbc, "Fixture Widget Webhook");
        listingId = AllocationFixtures.seedListing(jdbc, sellerId, canonicalProductId, "Fixture Widget Webhook", "149.00", 5);
    }

    private String paymentCapturedPayload(String razorpayOrderId, String paymentId) {
        return """
            {"entity":"event","event":"payment.captured","payload":{"payment":{"entity":{"id":"%s","order_id":"%s"}}}}
            """.formatted(paymentId, razorpayOrderId).strip();
    }

    @Test
    void webhook_withValidSignature_marksOrderPaidAndFulfillsReservation() throws Exception {
        CheckoutResult checkout = checkoutService.checkout(
            new CheckoutRequest("buyer@example.com", List.of(new CartLine(listingId, 1)), AllocationFixtures.LOCAL_PINCODE));
        String paymentId = "pay_webhook_" + UUID.randomUUID();
        String body = paymentCapturedPayload(checkout.razorpayOrderId(), paymentId);
        String signature = Utils.getHash(body, razorpayProps.webhookSecret());

        mockMvc.perform(post("/v1/webhooks/razorpay")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Razorpay-Signature", signature)
                .content(body))
            .andExpect(status().isOk());

        String orderStatus = jdbc.queryForObject(
            "SELECT status FROM orders WHERE id = ?", String.class, checkout.orderId());
        assertThat(orderStatus).isEqualTo("PAID");

        Boolean fulfilled = jdbc.queryForObject(
            "SELECT fulfilled_at IS NOT NULL FROM reservations WHERE order_id = ?", Boolean.class, checkout.orderId());
        assertThat(fulfilled).isTrue();
    }

    @Test
    void webhook_withTamperedSignature_isRejected_orderStaysUnpaid() throws Exception {
        CheckoutResult checkout = checkoutService.checkout(
            new CheckoutRequest("buyer@example.com", List.of(new CartLine(listingId, 1)), AllocationFixtures.LOCAL_PINCODE));
        String paymentId = "pay_webhook_" + UUID.randomUUID();
        String body = paymentCapturedPayload(checkout.razorpayOrderId(), paymentId);
        String tamperedSignature = Utils.getHash(body, "wrong-secret");

        mockMvc.perform(post("/v1/webhooks/razorpay")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Razorpay-Signature", tamperedSignature)
                .content(body))
            .andExpect(status().isBadRequest());

        String orderStatus = jdbc.queryForObject(
            "SELECT status FROM orders WHERE id = ?", String.class, checkout.orderId());
        assertThat(orderStatus).isEqualTo("CREATED");
    }

    @Test
    void webhook_forUnknownOrder_isAcceptedButChangesNothing() throws Exception {
        String body = paymentCapturedPayload("order_does_not_exist", "pay_unknown");
        String signature = Utils.getHash(body, razorpayProps.webhookSecret());

        // Accepted (200) so Razorpay doesn't retry forever - there is simply
        // no matching order to act on, which is not the same as a failure.
        mockMvc.perform(post("/v1/webhooks/razorpay")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Razorpay-Signature", signature)
                .content(body))
            .andExpect(status().isOk());
    }
}
