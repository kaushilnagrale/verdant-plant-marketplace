package com.verdant.orders;

import com.verdant.cart.CartLine;
import com.verdant.common.testing.IntegrationTestBase;
import com.verdant.orders.allocation.AllocationEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AllocationTest extends IntegrationTestBase {

    @Autowired private AllocationEngine engine;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void seedPincodeZones() {
        // Same pincodes as scripts/seed-pincodes.sql - not run automatically
        // against the Testcontainers database, only Flyway migrations are.
        jdbc.update("""
            INSERT INTO pincode_zones (pincode, state, zone, cod_eligible) VALUES
              ('411001','Maharashtra','LOCAL',TRUE),
              ('400001','Maharashtra','METRO',TRUE),
              ('560001','Karnataka','METRO',TRUE),
              ('110001','Delhi','NATIONAL',TRUE),
              ('797001','Nagaland','REMOTE',FALSE)
            ON CONFLICT DO NOTHING
            """);
    }

    /** Dedicated per-test canonical products, not the real seeded catalog - keeps every scenario's numbers under this test's own control. */
    private UUID canonicalProduct(String displayName, int maxTransitDays) {
        return jdbc.queryForObject("""
            INSERT INTO canonical_products (botanical_name, display_name, size_tier, heat_tolerance, max_transit_days)
            VALUES (?, ?, 'MEDIUM', 'HIGH', ?) RETURNING id
            """, UUID.class, "Test-" + UUID.randomUUID(), displayName, maxTransitDays);
    }

    private UUID seller(String name, String originPincode, String qualityScore, String status) {
        return jdbc.queryForObject("""
            INSERT INTO sellers (business_name, status, origin_pincode, quality_score)
            VALUES (?, ?, ?, ?) RETURNING id
            """, UUID.class, name, status, originPincode, new BigDecimal(qualityScore));
    }

    private UUID listing(UUID sellerId, UUID canonicalProductId, String price, int stock) {
        UUID listingId = jdbc.queryForObject("""
            INSERT INTO listings (seller_id, raw_title, price_amount, canonical_product_id)
            VALUES (?, 'Test Listing', ?, ?) RETURNING id
            """, UUID.class, sellerId, new BigDecimal(price), canonicalProductId);
        jdbc.update("INSERT INTO inventory (listing_id, quantity, buffer) VALUES (?, ?, 0)", listingId, stock);
        return listingId;
    }

    @Test
    void consolidationOpportunity_exceedingSafeTransit_isRejectedInFavourOfSplitting() {
        UUID p1 = canonicalProduct("Item1", 4);
        UUID p2 = canonicalProduct("Item2", 4);
        UUID p3 = canonicalProduct("Item3", 4);

        // seller A stocks all three but is too far (REMOTE origin -> METRO dest = 7 days, exceeds every item's 4-day limit)
        UUID sellerA = seller("Distant Consolidator", "797001", "0.90", "APPROVED");
        UUID listingA1 = listing(sellerA, p1, "100.00", 10);
        UUID listingA2 = listing(sellerA, p2, "100.00", 10);
        UUID listingA3 = listing(sellerA, p3, "100.00", 10);

        // sellers B/C/D each stock one, all comfortably within the safe window (METRO->METRO = 2 days)
        UUID sellerB = seller("Near B", "560001", "0.80", "APPROVED");
        UUID sellerC = seller("Near C", "400001", "0.80", "APPROVED");
        UUID sellerD = seller("Near D", "560001", "0.80", "APPROVED");
        listing(sellerB, p1, "110.00", 10);
        listing(sellerC, p2, "110.00", 10);
        listing(sellerD, p3, "110.00", 10);

        var plan = engine.plan(List.of(
            new CartLine(listingA1, 1), new CartLine(listingA2, 1), new CartLine(listingA3, 1)), "560001");

        assertThat(plan.shipments()).hasSizeGreaterThan(1);
        assertThat(plan.allShipments())
            .as("arriving dead in one parcel is worse than arriving alive in two")
            .allMatch(a -> a.transitDays() <= a.maxSafeTransitDays());
    }

    @Test
    void nearerSeller_winsOverCheaperDistantSeller() {
        UUID product = canonicalProduct("SingleItem", 6);
        UUID nearbySeller = seller("Nearby", "411001", "0.80", "APPROVED");   // LOCAL -> LOCAL = 1 day
        UUID distantSeller = seller("Distant Cheap", "560001", "0.80", "APPROVED"); // METRO -> LOCAL = 3 days

        UUID nearbyListing = listing(nearbySeller, product, "200.00", 10);
        listing(distantSeller, product, "50.00", 10); // much cheaper, but transit weight (0.55) dominates cost weight (0.05)

        var plan = engine.plan(List.of(new CartLine(nearbyListing, 1)), "411001");

        assertThat(plan.sellerFor(0)).isEqualTo(nearbySeller);
    }

    @Test
    void allocationDecision_isDeterministic_givenIdenticalInputs() {
        UUID product = canonicalProduct("DeterminismItem", 6);
        UUID sellerX = seller("X", "560001", "0.80", "APPROVED");
        UUID sellerY = seller("Y", "560001", "0.80", "APPROVED"); // identical zone/price/quality - forces the tiebreak
        UUID listingX = listing(sellerX, product, "100.00", 10);
        listing(sellerY, product, "100.00", 10);

        var cart = List.of(new CartLine(listingX, 1));
        var a = engine.plan(cart, "560001");
        var b = engine.plan(cart, "560001");

        assertThat(a.assignments()).isEqualTo(b.assignments());
    }

    @Test
    void fernAndSucculent_toRemotePincode_allocateDifferently() {
        UUID fern = canonicalProduct("Boston Fern", 2);   // LOW heat tolerance -> short safe transit
        UUID snake = canonicalProduct("Snake Plant", 9);  // HIGH heat tolerance -> long safe transit

        UUID s = seller("Remote Test Seller", "411001", "0.80", "APPROVED"); // LOCAL -> REMOTE = 7 days
        UUID fernListing = listing(s, fern, "150.00", 10);
        UUID snakeListing = listing(s, snake, "150.00", 10);

        var plan = engine.plan(List.of(new CartLine(fernListing, 1), new CartLine(snakeListing, 1)), "797001");

        assertThat(plan.unfulfillable()).extracting("productName").contains("Boston Fern");
        assertThat(plan.allocated()).extracting("productName").contains("Snake Plant");
    }

    @Test
    void unfulfillableLine_isSurfacedBeforePayment_notAfter() {
        UUID product = canonicalProduct("Unreachable Item", 2);
        UUID s = seller("Far Seller", "411001", "0.80", "APPROVED"); // LOCAL -> REMOTE = 7 days, exceeds the 2-day limit
        UUID listing = listing(s, product, "100.00", 10);

        var plan = engine.plan(List.of(new CartLine(listing, 1)), "797001");

        assertThat(plan.unfulfillable()).isNotEmpty();
        assertThat(plan.requiresBuyerConfirmation()).isTrue();
    }

    @Test
    void suspendedSeller_isNeverAllocated() {
        UUID product = canonicalProduct("SuspendTest Item", 6);
        UUID nearbySeller = seller("Nearby Suspended", "411001", "0.80", "APPROVED");
        UUID otherSeller = seller("Other", "560001", "0.80", "APPROVED"); // METRO -> LOCAL = 3 days, within the 6-day limit

        UUID nearbyListing = listing(nearbySeller, product, "100.00", 10);
        listing(otherSeller, product, "100.00", 10);

        jdbc.update("UPDATE sellers SET status = 'SUSPENDED' WHERE id = ?", nearbySeller);

        var plan = engine.plan(List.of(new CartLine(nearbyListing, 1)), "411001");

        assertThat(plan.sellerFor(0)).isNotEqualTo(nearbySeller);
        assertThat(plan.sellerFor(0)).isEqualTo(otherSeller);
    }
}
