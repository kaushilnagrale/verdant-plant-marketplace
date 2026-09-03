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

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OrderExpiryServiceTest extends IntegrationTestBase {

    @Autowired private CheckoutService checkoutService;
    @Autowired private PaymentVerificationService verification;
    @Autowired private OrderExpiryService orderExpiry;
    @Autowired private RazorpayProperties razorpayProps;
    @Autowired private JdbcTemplate jdbc;

    private UUID listingId;

    @BeforeEach
    void seedListing() {
        AllocationFixtures.seedLocalPincodeZone(jdbc);
        UUID sellerId = AllocationFixtures.seedApprovedSeller(jdbc, "Expiry Test Nursery");
        UUID canonicalProductId = AllocationFixtures.seedCanonicalProduct(jdbc, "Fixture Widget Expiry");
        listingId = AllocationFixtures.seedListing(jdbc, sellerId, canonicalProductId, "Fixture Widget Expiry", "299.00", 5);
    }

    private CheckoutResult startCheckout() {
        return checkoutService.checkout(
            new CheckoutRequest("buyer@example.com", List.of(new CartLine(listingId, 1)),
                AllocationFixtures.LOCAL_PINCODE));
    }

    private void backdateExpiry(UUID orderId) {
        jdbc.update("UPDATE orders SET expires_at = now() - interval '1 minute' WHERE id = ?", orderId);
    }

    private Integer currentStock() {
        return jdbc.queryForObject(
            "SELECT quantity FROM inventory WHERE listing_id = ?", Integer.class, listingId);
    }

    @Test
    void expireOrders_expiresUnpaidOrder_andReleasesItsReservation() {
        CheckoutResult checkout = startCheckout();
        backdateExpiry(checkout.orderId());
        assertThat(currentStock()).isEqualTo(4); // reserved, not yet released

        orderExpiry.expireOrders();

        String status = jdbc.queryForObject(
            "SELECT status FROM orders WHERE id = ?", String.class, checkout.orderId());
        assertThat(status).isEqualTo("EXPIRED");
        assertThat(currentStock()).isEqualTo(5); // credited back
    }

    @Test
    void expireOrders_neverTouchesAPaidOrder_evenPastItsWindow() throws Exception {
        CheckoutResult checkout = startCheckout();
        String paymentId = "pay_test_" + UUID.randomUUID();
        String signature = Utils.getHash(
            checkout.razorpayOrderId() + "|" + paymentId, razorpayProps.keySecret());
        verification.verifyPayment(checkout.orderId(),
            new PaymentVerificationRequest(checkout.razorpayOrderId(), paymentId, signature));

        // Payment already succeeded - now simulate the window having passed
        // by the time the sweep runs.
        backdateExpiry(checkout.orderId());

        orderExpiry.expireOrders();

        String status = jdbc.queryForObject(
            "SELECT status FROM orders WHERE id = ?", String.class, checkout.orderId());
        assertThat(status)
            .as("a paid order must never be flipped to EXPIRED by the sweep")
            .isEqualTo("PAID");
        assertThat(currentStock())
            .as("a fulfilled reservation's stock must never be released back")
            .isEqualTo(4);
    }

    @Test
    void expireOrders_leavesAnActiveOrderAlone() {
        CheckoutResult checkout = startCheckout(); // expires_at is 15 minutes out, untouched

        orderExpiry.expireOrders();

        String status = jdbc.queryForObject(
            "SELECT status FROM orders WHERE id = ?", String.class, checkout.orderId());
        assertThat(status).isEqualTo("CREATED");
        assertThat(currentStock()).isEqualTo(4);
    }
}
