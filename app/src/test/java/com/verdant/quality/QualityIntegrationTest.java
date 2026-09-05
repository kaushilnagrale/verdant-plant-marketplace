package com.verdant.quality;

import com.verdant.cart.CartLine;
import com.verdant.common.testing.IntegrationTestBase;
import com.verdant.orders.allocation.AllocationEngine;
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

/**
 * Day 54: the score wired into real allocation, not just computed and
 * left to sit in its own table. Same fixture style as AllocationTest
 * (Week 6) - a raw sellers.quality_score seed is enough for the two tests
 * that are really about the continuous score (2 and 5); the two tier-cap
 * tests (1 and 4) need a real seller_quality_scores row too, since
 * Tier.maxOpenOrders() is a hard gate CandidateFinder reads from there,
 * not from the continuous score.
 */
class QualityIntegrationTest extends IntegrationTestBase {

    @Autowired private AllocationEngine engine;
    @Autowired private JdbcTemplate jdbc;

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
              ('400001','Maharashtra','METRO',TRUE),
              ('560001','Karnataka','METRO',TRUE),
              ('797001','Nagaland','REMOTE',FALSE)
            ON CONFLICT DO NOTHING
            """);
    }

    private UUID canonicalProduct(String displayName, int maxTransitDays) {
        UUID id = jdbc.queryForObject("""
            INSERT INTO canonical_products (botanical_name, display_name, size_tier, heat_tolerance, max_transit_days)
            VALUES (?, ?, 'MEDIUM', 'HIGH', ?) RETURNING id
            """, UUID.class, "Test-" + UUID.randomUUID(), "[QualityIntegrationTest] " + displayName, maxTransitDays);
        createdCanonicalProducts.add(id);
        return id;
    }

    private UUID seller(String name, String originPincode, String qualityScore) {
        return jdbc.queryForObject("""
            INSERT INTO sellers (business_name, status, origin_pincode, quality_score)
            VALUES (?, 'APPROVED', ?, ?) RETURNING id
            """, UUID.class, name, originPincode, new BigDecimal(qualityScore));
    }

    private UUID listing(UUID sellerId, UUID canonicalProductId, String price, int stock) {
        UUID listingId = jdbc.queryForObject("""
            INSERT INTO listings (seller_id, raw_title, price_amount, canonical_product_id)
            VALUES (?, 'Test Listing', ?, ?) RETURNING id
            """, UUID.class, sellerId, new BigDecimal(price), canonicalProductId);
        jdbc.update("INSERT INTO inventory (listing_id, quantity, buffer) VALUES (?, ?, 0)", listingId, stock);
        return listingId;
    }

    private void setTier(UUID sellerId, String tier) {
        jdbc.update("""
            INSERT INTO seller_quality_scores (seller_id, score, components, order_count, is_provisional, tier)
            VALUES (?, ?, '{}'::jsonb, 30, false, ?)
            ON CONFLICT (seller_id) DO UPDATE SET tier = EXCLUDED.tier
            """, sellerId, tier.equals("RESTRICTED") ? new BigDecimal("0.40") : new BigDecimal("0.90"), tier);
    }

    /** An "open" shipment - anything not yet in a terminal state - the same definition CandidateFinder's own real cap query uses. */
    private void seedOpenShipment(UUID sellerId) {
        UUID orderId = jdbc.queryForObject("""
            INSERT INTO orders (buyer_email, status, total_amount, expires_at, paid_at)
            VALUES ('quality-buyer@example.com', 'PAID', 100.00, now() + interval '15 minutes', now())
            RETURNING id
            """, UUID.class);
        jdbc.update("INSERT INTO shipments (order_id, seller_id, status) VALUES (?, ?, 'BOOKED')", orderId, sellerId);
    }

    @Test
    void restrictedSeller_receivesReducedAllocationShare() {
        UUID product = canonicalProduct("RestrictedVsStandard", 6);
        UUID restrictedSeller = seller("Restricted Nursery", "411001", "0.40");
        setTier(restrictedSeller, "RESTRICTED");
        UUID standardSeller = seller("Standard Nursery", "411001", "0.80"); // same zone - equal transit

        UUID restrictedListing = listing(restrictedSeller, product, "100.00", 10);
        listing(standardSeller, product, "100.00", 10); // same price too - quality is the only real difference

        var plan = engine.plan(List.of(new CartLine(restrictedListing, 1)), "411001");

        assertThat(plan.sellerFor(0))
            .as("a comparable standard seller is preferred over a restricted one at equal transit and price")
            .isEqualTo(standardSeller);
    }

    @Test
    void preferredSeller_ranksAboveStandardAtEqualTransitTime() {
        UUID product = canonicalProduct("PreferredVsStandard", 6);
        UUID preferredSeller = seller("Preferred Nursery", "411001", "0.95");
        UUID standardSeller = seller("Standard Nursery", "411001", "0.70"); // same zone - equal transit

        UUID preferredListing = listing(preferredSeller, product, "100.00", 10);
        listing(standardSeller, product, "100.00", 10); // same price - quality is the only real difference

        var plan = engine.plan(List.of(new CartLine(preferredListing, 1)), "411001");

        assertThat(plan.sellerFor(0)).isEqualTo(preferredSeller);
    }

    @Test
    void probationSellerAtOrderCap_isSkippedByAllocator() {
        UUID product = canonicalProduct("ProbationAtCap", 6);
        UUID probationSeller = seller("Probation Nursery", "411001", "0.70");
        setTier(probationSeller, "PROBATION");
        for (int i = 0; i < 5; i++) { // Tier.PROBATION.maxOpenOrders() == 5
            seedOpenShipment(probationSeller);
        }
        UUID otherSeller = seller("Uncapped Nursery", "560001", "0.70"); // farther, but the only real candidate left

        UUID probationListing = listing(probationSeller, product, "100.00", 10);
        listing(otherSeller, product, "100.00", 10);

        var plan = engine.plan(List.of(new CartLine(probationListing, 1)), "411001");

        assertThat(plan.sellerFor(0))
            .as("a seller at their tier's own open-order cap is not a real candidate at all, regardless of price/transit")
            .isEqualTo(otherSeller);
    }

    @Test
    void transitTime_stillOutweighsQuality_forDistantPreferredSeller() {
        UUID product = canonicalProduct("TransitOutweighsQuality", 6);
        UUID nearbyStandard = seller("Nearby Standard", "411001", "0.65");   // LOCAL -> LOCAL = 1 day
        UUID distantPreferred = seller("Distant Preferred", "560001", "0.98"); // METRO -> LOCAL = 3 days

        UUID nearbyListing = listing(nearbyStandard, product, "100.00", 10);
        listing(distantPreferred, product, "100.00", 10);

        var plan = engine.plan(List.of(new CartLine(nearbyListing, 1)), "411001");

        assertThat(plan.sellerFor(0))
            .as("transit stays dominant (weight 0.55) over quality (weight 0.25) - a plant arriving alive beats a marginally better-rated seller several days away")
            .isEqualTo(nearbyStandard);
    }
}
