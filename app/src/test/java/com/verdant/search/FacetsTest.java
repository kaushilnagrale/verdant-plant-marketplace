package com.verdant.search;

import com.verdant.common.testing.AllocationFixtures;
import com.verdant.common.testing.SearchIntegrationTestBase;
import com.verdant.search.projection.ReindexWorker;
import com.verdant.search.projection.SearchReindexQueueRepository;
import com.verdant.search.query.FacetCounts;
import com.verdant.search.query.ListingSearchService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.core.BulkRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Facets (Day 50.3) are real aggregations over the FULL match set, not
 * just the page of hits returned. Each test seeds its own canonical
 * product(s) under a globally unique display name (never reused across
 * runs) so a multiMatch search on that exact name can only ever match
 * documents this one test method created - giving exact bucket counts
 * even though, like RankingTest, the underlying OpenSearch index is
 * shared JVM-wide and never cleaned between tests or test runs.
 */
class FacetsTest extends SearchIntegrationTestBase {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private OpenSearchClient opensearch;
    @Autowired private SearchReindexQueueRepository reindexQueue;
    @Autowired private ReindexWorker reindexWorker;
    @Autowired private ListingSearchService searchService;

    private final List<UUID> indexedListingIds = new ArrayList<>();

    @BeforeEach
    void seedZones() {
        AllocationFixtures.seedLocalPincodeZone(jdbc);
        AllocationFixtures.seedMetroPincodeZone(jdbc);
    }

    /** Same real accumulation bug RankingTest's own @AfterEach documents - a failed OR passed run both leave real documents behind under OPENSEARCH.withReuse(true), and enough of them (dozens, from iterating on this exact file earlier today) eventually pollute a supposedly-unique-token query too. Deleting exactly what each run indexed keeps the index from ever accumulating past what one run needs. */
    @AfterEach
    void deleteIndexedDocuments() throws Exception {
        if (indexedListingIds.isEmpty()) {
            return;
        }
        BulkRequest.Builder request = new BulkRequest.Builder();
        for (UUID id : indexedListingIds) {
            request.operations(op -> op.delete(del -> del.index("listings").id(id.toString())));
        }
        opensearch.bulk(request.build());
    }

    /**
     * Hand-rolled rather than AllocationFixtures.seedCanonicalProduct():
     * that helper doesn't set light_requirement/pet_safe, which these
     * tests are specifically about. Still uses its "[Fixture] " prefix so
     * AllocationFixtureCleanupExtension finds and removes the Postgres
     * row afterward.
     *
     * REAL BUG, found by every one of these tests failing the first time
     * they ran: display_name included ordinary English words ("Facet
     * Price Plant"), and multiMatch's default operator is OR across
     * terms - a query for "Facet Price Plant <token>" also matches every
     * OTHER document anywhere in this shared, never-cleaned index that
     * merely contains the word "plant" (i.e. nearly all of them), pulling
     * in dozens of unrelated documents into what was meant to be an
     * exact count. token is a bare UUID, embedded in display_name for
     * human-readable debugging but used ALONE as the search query (see
     * each test below) - its hex fragments are astronomically unlikely
     * to collide with anything real already in the corpus, unlike an
     * ordinary word.
     */
    private UUID seedFacetProduct(String token, String lightRequirement, boolean petSafe, String sizeTier) {
        return jdbc.queryForObject("""
            INSERT INTO canonical_products
                (botanical_name, display_name, size_tier, heat_tolerance, max_transit_days, light_requirement, pet_safe)
            VALUES (?, ?, ?, 'HIGH', 30, ?, ?) RETURNING id
            """, UUID.class,
            "Fixture-" + UUID.randomUUID(),
            AllocationFixtures.DISPLAY_NAME_PREFIX + "Facet Test " + token,
            sizeTier, lightRequirement, petSafe);
    }

    private UUID indexListing(UUID sellerId, UUID canonicalProductId, String priceAmount, int stock) {
        UUID listingId = AllocationFixtures.seedListing(jdbc, sellerId, canonicalProductId,
            "Facet test listing " + UUID.randomUUID(), priceAmount, stock);
        reindexQueue.enqueue(listingId, "TEST_SETUP");
        reindexWorker.drainBatch(List.of(listingId));
        indexedListingIds.add(listingId);
        return listingId;
    }

    private FacetCounts search(String text, String destinationPincode) throws Exception {
        opensearch.indices().refresh(r -> r.index("listings"));
        return searchService.search(text, destinationPincode).facets();
    }

    @Test
    void priceRangeFacet_bucketsRealPrices() throws Exception {
        String token = UUID.randomUUID().toString();
        UUID productId = seedFacetProduct(token, "LOW", false, "MEDIUM");
        UUID seller = AllocationFixtures.seedSeller(jdbc,
            "Facet Price Nursery " + UUID.randomUUID(), "APPROVED", AllocationFixtures.LOCAL_PINCODE);
        indexListing(seller, productId, "250.00", 5);   // under_300
        indexListing(seller, productId, "450.00", 5);   // 300_to_600
        indexListing(seller, productId, "900.00", 5);   // 600_to_1200
        indexListing(seller, productId, "1500.00", 5);  // over_1200

        FacetCounts facets = search(token, AllocationFixtures.LOCAL_PINCODE);

        assertThat(facets.priceRange())
            .containsEntry("under_300", 1L)
            .containsEntry("300_to_600", 1L)
            .containsEntry("600_to_1200", 1L)
            .containsEntry("over_1200", 1L);
    }

    @Test
    void lightRequirementAndSizeTierAndPetSafeFacets_reflectRealCatalogFields() throws Exception {
        String token = UUID.randomUUID().toString();
        UUID lowLightPetSafe = seedFacetProduct(token, "LOW", true, "SMALL");
        UUID brightLightNotPetSafe = seedFacetProduct(token, "BRIGHT_INDIRECT", false, "LARGE");
        UUID seller = AllocationFixtures.seedSeller(jdbc,
            "Facet Attribute Nursery " + UUID.randomUUID(), "APPROVED", AllocationFixtures.LOCAL_PINCODE);
        indexListing(seller, lowLightPetSafe, "300.00", 5);
        indexListing(seller, brightLightNotPetSafe, "300.00", 5);

        FacetCounts facets = search(token, AllocationFixtures.LOCAL_PINCODE);

        assertThat(facets.lightRequirement()).containsEntry("LOW", 1L).containsEntry("BRIGHT_INDIRECT", 1L);
        assertThat(facets.sizeTier()).containsEntry("SMALL", 1L).containsEntry("LARGE", 1L);
        assertThat(facets.petSafe()).containsEntry("true", 1L).containsEntry("false", 1L);
    }

    @Test
    void deliveryDaysFacet_reflectsRealTransitTimeToDestination() throws Exception {
        String token = UUID.randomUUID().toString();
        UUID productId = seedFacetProduct(token, "LOW", false, "MEDIUM");
        UUID nearSeller = AllocationFixtures.seedSeller(jdbc,
            "Facet Delivery Near Nursery " + UUID.randomUUID(), "APPROVED", AllocationFixtures.LOCAL_PINCODE);
        UUID farSeller = AllocationFixtures.seedSeller(jdbc,
            "Facet Delivery Far Nursery " + UUID.randomUUID(), "APPROVED", AllocationFixtures.METRO_PINCODE);
        indexListing(nearSeller, productId, "300.00", 5);
        indexListing(farSeller, productId, "300.00", 5);

        FacetCounts facets = search(token, AllocationFixtures.LOCAL_PINCODE);

        // Real transit_estimates (V20): LOCAL -> LOCAL = 1 day, METRO -> LOCAL = 3 days.
        assertThat(facets.deliveryDays()).containsEntry("1 day", 1L).containsEntry("3 days", 1L);
    }
}
