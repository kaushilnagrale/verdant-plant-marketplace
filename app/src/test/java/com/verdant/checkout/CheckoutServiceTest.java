package com.verdant.checkout;

import com.verdant.cart.CartLine;
import com.verdant.common.testing.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CheckoutServiceTest extends IntegrationTestBase {

    @Autowired
    private CheckoutService checkoutService;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID inStockListing;
    private UUID outOfStockListing;

    @BeforeEach
    void seedListings() {
        UUID sellerId = jdbc.queryForObject(
            "INSERT INTO sellers (business_name, status) VALUES ('Checkout Test Nursery', 'APPROVED') RETURNING id",
            UUID.class);

        inStockListing = jdbc.queryForObject("""
            INSERT INTO listings (seller_id, raw_title, price_amount) VALUES (?, 'Snake Plant', 349.00) RETURNING id
            """, UUID.class, sellerId);
        jdbc.update("INSERT INTO inventory (listing_id, quantity, buffer) VALUES (?, 5, 0)", inStockListing);

        outOfStockListing = jdbc.queryForObject("""
            INSERT INTO listings (seller_id, raw_title, price_amount) VALUES (?, 'Rare Bonsai', 2999.00) RETURNING id
            """, UUID.class, sellerId);
        jdbc.update("INSERT INTO inventory (listing_id, quantity, buffer) VALUES (?, 0, 0)", outOfStockListing);
    }

    @Test
    void checkout_reservesStockCreatesRazorpayOrderAndPersistsOrder() {
        CheckoutResult result = checkoutService.checkout(
            new CheckoutRequest("buyer@example.com", List.of(new CartLine(inStockListing, 2))));

        assertThat(result.razorpayOrderId()).isNotBlank();
        assertThat(result.totalAmount().amount()).isEqualByComparingTo("698.00"); // 349.00 x 2

        Integer remainingStock = jdbc.queryForObject(
            "SELECT quantity FROM inventory WHERE listing_id = ?", Integer.class, inStockListing);
        assertThat(remainingStock).isEqualTo(3); // 5 - 2

        String orderStatus = jdbc.queryForObject(
            "SELECT status FROM orders WHERE id = ?", String.class, result.orderId());
        assertThat(orderStatus).isEqualTo("CREATED");
    }

    @Test
    void checkout_partialInsufficientStock_rollsBackTheWholeCart_reservesNothing() {
        // First line has plenty of stock, second doesn't - the whole
        // checkout must fail atomically, not partially reserve the first.
        assertThatThrownBy(() -> checkoutService.checkout(new CheckoutRequest("buyer@example.com",
                List.of(new CartLine(inStockListing, 1), new CartLine(outOfStockListing, 1)))))
            .isInstanceOf(ResponseStatusException.class);

        Integer remainingStock = jdbc.queryForObject(
            "SELECT quantity FROM inventory WHERE listing_id = ?", Integer.class, inStockListing);
        assertThat(remainingStock)
            .as("the in-stock line must not stay reserved when the cart as a whole fails")
            .isEqualTo(5);

        Integer reservationCount = jdbc.queryForObject(
            "SELECT count(*) FROM reservations WHERE listing_id = ?", Integer.class, inStockListing);
        assertThat(reservationCount).isZero();
    }
}
