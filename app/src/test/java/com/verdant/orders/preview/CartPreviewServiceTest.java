package com.verdant.orders.preview;

import com.verdant.cart.CartLine;
import com.verdant.common.testing.IntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CartPreviewServiceTest extends IntegrationTestBase {

    @Autowired private CartPreviewService previewService;
    @Autowired private JdbcTemplate jdbc;

    // Same cross-test-suite pollution lesson as AllocationTest (Day 32/33):
    // canonical_products is shared with Week 3's mapping pipeline, so
    // anything created here must be cleaned up, not just carefully named.
    private final List<UUID> createdCanonicalProducts = new ArrayList<>();

    @AfterEach
    void cleanUpTestCanonicalProducts() {
        for (UUID id : createdCanonicalProducts) {
            jdbc.update("""
                DELETE FROM inventory WHERE listing_id IN (
                  SELECT id FROM listings WHERE canonical_product_id = ?)
                """, id);
            jdbc.update("DELETE FROM listings WHERE canonical_product_id = ?", id);
            jdbc.update("DELETE FROM canonical_products WHERE id = ?", id);
        }
    }

    @BeforeEach
    void seedPincodeZones() {
        jdbc.update("""
            INSERT INTO pincode_zones (pincode, state, zone, cod_eligible) VALUES
              ('411001','Maharashtra','LOCAL',TRUE),
              ('560001','Karnataka','METRO',TRUE),
              ('797001','Nagaland','REMOTE',FALSE)
            ON CONFLICT DO NOTHING
            """);
    }

    private UUID canonicalProduct(String displayName, int maxTransitDays) {
        UUID id = jdbc.queryForObject("""
            INSERT INTO canonical_products (botanical_name, display_name, size_tier, heat_tolerance, max_transit_days)
            VALUES (?, ?, 'MEDIUM', 'HIGH', ?) RETURNING id
            """, UUID.class, "Test-" + UUID.randomUUID(), "[CartPreviewServiceTest] " + displayName, maxTransitDays);
        createdCanonicalProducts.add(id);
        return id;
    }

    private UUID seller(String name, String originPincode) {
        return jdbc.queryForObject("""
            INSERT INTO sellers (business_name, status, origin_pincode, quality_score)
            VALUES (?, 'APPROVED', ?, 0.80) RETURNING id
            """, UUID.class, name, originPincode);
    }

    private UUID listing(UUID sellerId, UUID canonicalProductId, String price, int stock) {
        UUID listingId = jdbc.queryForObject("""
            INSERT INTO listings (seller_id, raw_title, price_amount, canonical_product_id)
            VALUES (?, 'Preview Test Listing', ?, ?) RETURNING id
            """, UUID.class, sellerId, new BigDecimal(price), canonicalProductId);
        jdbc.update("INSERT INTO inventory (listing_id, quantity, buffer) VALUES (?, ?, 0)", listingId, stock);
        return listingId;
    }

    @Test
    void singleFulfillableLine_producesOneShipment_withCorrectTransitAndCost() {
        UUID product = canonicalProduct("Single Item", 6);
        UUID seller = seller("Nearby Seller", "411001");
        UUID listing = listing(seller, product, "100.00", 10);

        CartPreviewResponse preview = previewService.preview(List.of(new CartLine(listing, 1)), "411001");

        assertThat(preview.shipments()).hasSize(1);
        assertThat(preview.unfulfillable()).isEmpty();
        assertThat(preview.requiresBuyerConfirmation()).isFalse();
        assertThat(preview.shipments().get(0).transitDays()).isEqualTo(1); // LOCAL -> LOCAL
        assertThat(preview.totalShippingCost().amount()).isEqualByComparingTo("49.00");
    }

    @Test
    void multipleShipments_totalShippingCostRespectsThePlatformCap() {
        UUID p1 = canonicalProduct("Item A", 6);
        UUID p2 = canonicalProduct("Item B", 6);
        UUID p3 = canonicalProduct("Item C", 6);

        UUID sellerA = seller("Seller A", "411001");
        UUID sellerB = seller("Seller B", "560001");
        UUID sellerC = seller("Seller C", "560001");

        UUID l1 = listing(sellerA, p1, "100.00", 10);
        UUID l2 = listing(sellerB, p2, "100.00", 10);
        UUID l3 = listing(sellerC, p3, "100.00", 10);

        CartPreviewResponse preview = previewService.preview(
            List.of(new CartLine(l1, 1), new CartLine(l2, 1), new CartLine(l3, 1)), "560001");

        assertThat(preview.shipments()).hasSize(3);
        // 3 shipments x 49.00 = 147.00, but the platform cap is 99.00
        assertThat(preview.totalShippingCost().amount()).isEqualByComparingTo("99.00");
    }

    @Test
    void unfulfillableLine_requiresBuyerConfirmation() {
        UUID product = canonicalProduct("Unreachable Item", 2);
        UUID seller = seller("Far Seller", "411001"); // LOCAL -> REMOTE = 7 days, exceeds the 2-day limit
        UUID listing = listing(seller, product, "100.00", 10);

        CartPreviewResponse preview = previewService.preview(List.of(new CartLine(listing, 1)), "797001");

        assertThat(preview.unfulfillable()).isNotEmpty();
        assertThat(preview.requiresBuyerConfirmation()).isTrue();
    }
}
