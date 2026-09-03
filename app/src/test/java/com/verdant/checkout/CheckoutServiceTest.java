package com.verdant.checkout;

import com.verdant.cart.CartLine;
import com.verdant.common.testing.AllocationFixtures;
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
    private UUID sellerId;

    @BeforeEach
    void seedListings() {
        AllocationFixtures.seedLocalPincodeZone(jdbc);
        sellerId = AllocationFixtures.seedApprovedSeller(jdbc, "Checkout Test Nursery");

        UUID snakePlant = AllocationFixtures.seedCanonicalProduct(jdbc, "Fixture Widget Alpha");
        inStockListing = AllocationFixtures.seedListing(jdbc, sellerId, snakePlant, "Snake Plant", "349.00", 5);

        UUID rareBonsai = AllocationFixtures.seedCanonicalProduct(jdbc, "Fixture Widget Beta");
        outOfStockListing = AllocationFixtures.seedListing(jdbc, sellerId, rareBonsai, "Rare Bonsai", "2999.00", 0);
    }

    @Test
    void checkout_reservesStockCreatesRazorpayOrderAndPersistsOrder() {
        CheckoutResult result = checkoutService.checkout(
            new CheckoutRequest("buyer@example.com", List.of(new CartLine(inStockListing, 2)),
                AllocationFixtures.LOCAL_PINCODE));

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
    void checkout_persistsOrderItems_withSellerAndPriceBreakdown() {
        CheckoutResult result = checkoutService.checkout(
            new CheckoutRequest("buyer@example.com", List.of(new CartLine(inStockListing, 2)),
                AllocationFixtures.LOCAL_PINCODE));

        var items = jdbc.queryForList(
            "SELECT listing_id, seller_id, quantity, unit_price, line_total FROM order_items WHERE order_id = ?",
            result.orderId());

        assertThat(items).hasSize(1);
        var item = items.get(0);
        assertThat(item.get("listing_id")).isEqualTo(inStockListing);
        assertThat(item.get("seller_id")).isEqualTo(sellerId);
        assertThat(item.get("quantity")).isEqualTo(2);
        assertThat((java.math.BigDecimal) item.get("unit_price")).isEqualByComparingTo("349.00");
        assertThat((java.math.BigDecimal) item.get("line_total")).isEqualByComparingTo("698.00");
    }

    @Test
    void checkout_partialInsufficientStock_rollsBackTheWholeCart_reservesNothing() {
        // First line has plenty of stock, second doesn't - the whole
        // checkout must fail atomically, not partially reserve the first.
        assertThatThrownBy(() -> checkoutService.checkout(new CheckoutRequest("buyer@example.com",
                List.of(new CartLine(inStockListing, 1), new CartLine(outOfStockListing, 1)),
                AllocationFixtures.LOCAL_PINCODE)))
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
