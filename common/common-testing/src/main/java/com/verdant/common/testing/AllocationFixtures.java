package com.verdant.common.testing;

import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Real checkout now runs real allocation (Week 7 - see CheckoutService),
 * which means every test that checks out a cart needs a genuinely
 * allocation-eligible fixture: a canonical product, a seller with an
 * origin_pincode/quality_score, and a pincode_zones row for whatever
 * destination the test uses (transit_estimates is seeded by V20 itself,
 * so it's already present in every test database - only pincode_zones is
 * real/environment data and has to be seeded per test, same as
 * AllocationTest already did before this existed).
 *
 * A single shared LOCAL pincode for both origin and destination keeps every
 * caller's transit math trivial (1 day, comfortably under any max_transit_days)
 * so tests that care about checkout/payment/saga behavior - not allocation
 * edge cases, which AllocationTest already covers - never fail for reasons
 * unrelated to what they're actually testing.
 */
public final class AllocationFixtures {

    public static final String LOCAL_PINCODE = "411001";

    // Real METRO/REMOTE pincodes, for tests (Week 9 ranking/serviceability)
    // that need more than one zone to exist at once - a single shared LOCAL
    // pincode is deliberately not enough once a test cares about the
    // zone-distance signal itself (nearer-ranks-higher) or the real
    // transit_estimates-driven serviceability filter (Day 50), rather than
    // just keeping transit math out of the way as LOCAL_PINCODE does above.
    public static final String METRO_PINCODE  = "400001"; // Mumbai GPO
    public static final String REMOTE_PINCODE = "797001"; // Along, Arunachal Pradesh

    private AllocationFixtures() { }

    public static void seedLocalPincodeZone(JdbcTemplate jdbc) {
        jdbc.update("""
            INSERT INTO pincode_zones (pincode, state, zone, cod_eligible)
            VALUES (?, 'Maharashtra', 'LOCAL', TRUE)
            ON CONFLICT DO NOTHING
            """, LOCAL_PINCODE);
    }

    public static void seedMetroPincodeZone(JdbcTemplate jdbc) {
        jdbc.update("""
            INSERT INTO pincode_zones (pincode, state, zone, cod_eligible)
            VALUES (?, 'Maharashtra', 'METRO', TRUE)
            ON CONFLICT DO NOTHING
            """, METRO_PINCODE);
    }

    public static void seedRemotePincodeZone(JdbcTemplate jdbc) {
        jdbc.update("""
            INSERT INTO pincode_zones (pincode, state, zone, cod_eligible)
            VALUES (?, 'Arunachal Pradesh', 'REMOTE', TRUE)
            ON CONFLICT DO NOTHING
            """, REMOTE_PINCODE);
    }

    /**
     * "[Fixture] " prefix is load-bearing, not cosmetic: canonical_products
     * is shared platform-wide with the Week 3 catalog-mapping pipeline, and
     * MappingAccuracyTest measures precision over every row in it. A row
     * left behind here without a way to find it again silently corrupted
     * that unrelated test's own measurement in the same reused
     * Testcontainers database (0.92 -> 0.88, a real regression this
     * fixture itself caused - the exact Day-33 AllocationTest incident,
     * repeated because this helper didn't carry that lesson over the first
     * time). AllocationFixtureCleanupExtension (registered on
     * IntegrationTestBase) finds and deletes every row by this prefix
     * after each test, so every caller gets the cleanup for free instead
     * of each of the 6+ test files needing its own @AfterEach.
     *
     * Generous max_transit_days (30) - this fixture is for tests that
     * aren't exercising transit-limit rejection.
     */
    public static final String DISPLAY_NAME_PREFIX = "[Fixture] ";

    public static UUID seedCanonicalProduct(JdbcTemplate jdbc, String displayName) {
        return jdbc.queryForObject("""
            INSERT INTO canonical_products (botanical_name, display_name, size_tier, heat_tolerance, max_transit_days)
            VALUES (?, ?, 'MEDIUM', 'HIGH', 30) RETURNING id
            """, UUID.class, "Fixture-" + UUID.randomUUID(), DISPLAY_NAME_PREFIX + displayName);
    }

    public static UUID seedApprovedSeller(JdbcTemplate jdbc, String businessName) {
        return seedSeller(jdbc, businessName, "APPROVED", LOCAL_PINCODE);
    }

    /**
     * General form behind seedApprovedSeller above - lets a test control
     * status (e.g. SUSPENDED, so ProjectionQueryRepository's real
     * `active = l.active AND s.status = 'APPROVED'` join correctly
     * excludes it - see RankingTest.suspendedSellerListings_neverAppear)
     * and origin pincode (e.g. METRO_PINCODE/REMOTE_PINCODE above, so a
     * test can control the seller's real origin_zone for ranking/
     * serviceability - see RankingTest) independently. status is passed
     * as a raw string rather than SellerStatus: this is a direct fixture
     * insert bypassing SellerOnboardingService's own state machine
     * entirely (same as the rest of this class), so there's no
     * transition to validate here, just a row to seed.
     */
    public static UUID seedSeller(JdbcTemplate jdbc, String businessName, String status, String originPincode) {
        return jdbc.queryForObject("""
            INSERT INTO sellers (business_name, status, origin_pincode, quality_score)
            VALUES (?, ?, ?, 0.80) RETURNING id
            """, UUID.class, businessName, status, originPincode);
    }

    public static UUID seedListing(JdbcTemplate jdbc, UUID sellerId, UUID canonicalProductId,
                                    String title, String priceAmount, int stock) {
        UUID listingId = jdbc.queryForObject("""
            INSERT INTO listings (seller_id, raw_title, price_amount, canonical_product_id, mapping_status)
            VALUES (?, ?, ?, ?, 'MANUAL') RETURNING id
            """, UUID.class, sellerId, title, new BigDecimal(priceAmount), canonicalProductId);
        jdbc.update("INSERT INTO inventory (listing_id, quantity, buffer) VALUES (?, ?, 0)", listingId, stock);
        return listingId;
    }
}
