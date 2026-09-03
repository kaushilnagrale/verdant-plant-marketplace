package com.verdant.checkout;

import com.razorpay.Utils;
import com.verdant.cart.CartLine;
import com.verdant.common.testing.AllocationFixtures;
import com.verdant.common.testing.IntegrationTestBase;
import com.verdant.payment.RazorpayProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Signature verification is pure HMAC math (no network call), so these
 * tests compute a signature the same way Razorpay would - using the same
 * test-only secret configured in application-test.yml - and check our code
 * accepts a correct one and rejects a tampered one.
 */
class PaymentVerificationServiceTest extends IntegrationTestBase {

    @Autowired private CheckoutService checkoutService;
    @Autowired private PaymentVerificationService verification;
    @Autowired private RazorpayProperties razorpayProps;
    @Autowired private JdbcTemplate jdbc;

    private UUID listingId;

    @BeforeEach
    void seedListing() {
        AllocationFixtures.seedLocalPincodeZone(jdbc);
        UUID sellerId = AllocationFixtures.seedApprovedSeller(jdbc, "Payment Test Nursery");
        UUID canonicalProductId = AllocationFixtures.seedCanonicalProduct(jdbc, "Fixture Widget Payment");
        listingId = AllocationFixtures.seedListing(jdbc, sellerId, canonicalProductId, "Fixture Widget Payment", "199.00", 5);
    }

    private CheckoutResult startCheckout() {
        return checkoutService.checkout(
            new CheckoutRequest("buyer@example.com", List.of(new CartLine(listingId, 1)),
                AllocationFixtures.LOCAL_PINCODE));
    }

    private String signatureFor(String razorpayOrderId, String paymentId, String secret) throws Exception {
        return Utils.getHash(razorpayOrderId + "|" + paymentId, secret);
    }

    @Test
    void verifyPayment_withValidSignature_marksOrderPaidAndFulfillsReservation() throws Exception {
        CheckoutResult checkout = startCheckout();
        String paymentId = "pay_test_" + UUID.randomUUID();
        String signature = signatureFor(checkout.razorpayOrderId(), paymentId, razorpayProps.keySecret());

        verification.verifyPayment(checkout.orderId(),
            new PaymentVerificationRequest(checkout.razorpayOrderId(), paymentId, signature));

        String status = jdbc.queryForObject(
            "SELECT status FROM orders WHERE id = ?", String.class, checkout.orderId());
        assertThat(status).isEqualTo("PAID");

        Boolean fulfilled = jdbc.queryForObject(
            "SELECT fulfilled_at IS NOT NULL FROM reservations WHERE order_id = ?", Boolean.class, checkout.orderId());
        assertThat(fulfilled).isTrue();
    }

    @Test
    void verifyPayment_withTamperedSignature_isRejected_orderStaysUnpaid() throws Exception {
        CheckoutResult checkout = startCheckout();
        String paymentId = "pay_test_" + UUID.randomUUID();
        String tamperedSignature = signatureFor(checkout.razorpayOrderId(), paymentId, "not-the-real-secret");

        assertThatThrownBy(() -> verification.verifyPayment(checkout.orderId(),
                new PaymentVerificationRequest(checkout.razorpayOrderId(), paymentId, tamperedSignature)))
            .isInstanceOf(ResponseStatusException.class);

        String status = jdbc.queryForObject(
            "SELECT status FROM orders WHERE id = ?", String.class, checkout.orderId());
        assertThat(status).isEqualTo("CREATED");

        Boolean fulfilled = jdbc.queryForObject(
            "SELECT fulfilled_at IS NOT NULL FROM reservations WHERE order_id = ?", Boolean.class, checkout.orderId());
        assertThat(fulfilled).isFalse();
    }

    @Test
    void verifyPayment_calledTwice_secondCallIsRejected_doesNotReprocess() throws Exception {
        CheckoutResult checkout = startCheckout();
        String paymentId = "pay_test_" + UUID.randomUUID();
        String signature = signatureFor(checkout.razorpayOrderId(), paymentId, razorpayProps.keySecret());
        PaymentVerificationRequest request =
            new PaymentVerificationRequest(checkout.razorpayOrderId(), paymentId, signature);

        verification.verifyPayment(checkout.orderId(), request);
        Instant firstPaidAt = jdbc.queryForObject(
            "SELECT paid_at FROM orders WHERE id = ?", Instant.class, checkout.orderId());

        assertThatThrownBy(() -> verification.verifyPayment(checkout.orderId(), request))
            .isInstanceOf(ResponseStatusException.class);

        Instant paidAtAfterRetry = jdbc.queryForObject(
            "SELECT paid_at FROM orders WHERE id = ?", Instant.class, checkout.orderId());
        assertThat(paidAtAfterRetry)
            .as("a retried verification must not reprocess an already-paid order")
            .isEqualTo(firstPaidAt);
    }
}
