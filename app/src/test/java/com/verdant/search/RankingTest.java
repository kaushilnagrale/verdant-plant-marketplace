package com.verdant.search;

import com.verdant.common.testing.AllocationFixtures;
import com.verdant.common.testing.SearchIntegrationTestBase;
import com.verdant.search.projection.ReindexWorker;
import com.verdant.search.projection.SearchReindexQueueRepository;
import com.verdant.search.query.ListingSearchService;
import com.verdant.search.query.SearchHit;
import com.verdant.search.query.SearchResults;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the real ListingSearchService (Day 50) against a real
 * OpenSearch index, built through the same real
 * ReindexWorker/ProjectionQueryRepository pipeline Day 49's ProjectionTest
 * already proved end to end - seeded here through direct JDBC fixture rows
 * (AllocationFixtures) plus a direct reindexQueue.enqueue()/
 * reindexWorker.drainBatch() rather than the full HTTP-create-listing
 * path, since these tests are about ranking/filtering behavior once a
 * document is correctly in the index, not about the async
 * outbox->Kafka->projector plumbing itself (that's what ProjectionTest is
 * for).
 *
 * refreshIndex() is the one deliberate real-infrastructure detail beyond
 * ProjectionTest's own pattern: ReindexWorker's bulk write (correctly, for
 * production - forcing a refresh on every write would be a real,
 * needless indexing-throughput cost at scale) does not request a refresh,
 * so a freshly-indexed document is visible to a real-time GET (which is
 * all ProjectionTest's awaitIndexed() ever needed) before it is visible to
 * _search, which only sees it after the next refresh cycle (default
 * every 1s). Every helper below calls the real Indices Refresh API
 * directly before searching - the same thing a production caller needing
 * read-your-own-write search consistency would have to do explicitly -
 * rather than papering over the real gap with a sleep.
 *
 * The "listings" index is shared JVM-wide (SearchIntegrationTestBase's
 * containers are static, and OPENSEARCH.withReuse(true) persists it across
 * separate test runs too, same as every other reused Testcontainers
 * instance in this project) - so, same reasoning already applied to
 * Postgres-backed tests elsewhere (ReconciliationTest's nanoTime-salted
 * dates, AllocationFixtures' own "[Fixture] " prefix), assertions here are
 * written to hold regardless of whatever unrelated documents other test
 * classes have left behind, not just in a pristine index. Where the
 * runbook's own version of a test would assert an absolute rank
 * (hits().get(0)), this uses a relative rank between this test's own two
 * sellers instead - see nearerSeller_ranksAboveCheaperDistantSeller.
 */
class RankingTest extends SearchIntegrationTestBase {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private OpenSearchClient opensearch;
    @Autowired private SearchReindexQueueRepository reindexQueue;
    @Autowired private ReindexWorker reindexWorker;
    @Autowired private ListingSearchService searchService;

    private UUID moneyPlantId;
    private UUID bostonFernId;

    @BeforeEach
    void seedZonesAndProducts() {
        AllocationFixtures.seedLocalPincodeZone(jdbc);
        AllocationFixtures.seedMetroPincodeZone(jdbc);
        AllocationFixtures.seedRemotePincodeZone(jdbc);
        moneyPlantId = canonicalProductId("Money Plant");
        bostonFernId = canonicalProductId("Boston Fern");
    }

    private UUID canonicalProductId(String displayName) {
        return jdbc.queryForObject(
            "SELECT id FROM canonical_products WHERE display_name = ?", UUID.class, displayName);
    }

    private UUID seedSeller(String businessName, String originPincode) {
        return AllocationFixtures.seedSeller(jdbc, businessName, "APPROVED", originPincode);
    }

    private UUID indexListing(UUID sellerId, UUID canonicalProductId, String priceAmount, int stock) {
        UUID listingId = AllocationFixtures.seedListing(jdbc, sellerId, canonicalProductId,
            "Ranking test listing " + UUID.randomUUID(), priceAmount, stock);
        reindexQueue.enqueue(listingId, "TEST_SETUP");
        reindexWorker.drainBatch(List.of(listingId));
        return listingId;
    }

    private SearchResults search(String text, String destinationPincode) throws Exception {
        refreshIndex();
        return searchService.search(text, destinationPincode);
    }

    private void refreshIndex() throws Exception {
        opensearch.indices().refresh(r -> r.index("listings"));
    }

    private int rankOf(SearchResults results, UUID sellerId) {
        List<SearchHit> hits = results.hits();
        for (int i = 0; i < hits.size(); i++) {
            if (hits.get(i).sellerId().equals(sellerId)) {
                return i;
            }
        }
        throw new AssertionError("Seller " + sellerId + " not found in results: " + hits);
    }

    @Test
    void nearerSeller_ranksAboveCheaperDistantSeller() throws Exception {
        // Money Plant's real max_transit_days is 5 (V8) - METRO is 3 days
        // from LOCAL (V20), safely within budget, so the far seller is
        // genuinely ranked lower rather than filtered out as unserviceable
        // (that's a different scenario, covered by unserviceableOrigin_isFilteredOut below).
        UUID nearSeller = seedSeller("Ranking Near Nursery " + UUID.randomUUID(), AllocationFixtures.LOCAL_PINCODE);
        UUID farSeller = seedSeller("Ranking Far Nursery " + UUID.randomUUID(), AllocationFixtures.METRO_PINCODE);
        indexListing(nearSeller, moneyPlantId, "399.00", 5);
        indexListing(farSeller, moneyPlantId, "299.00", 5);

        var results = search("money plant", AllocationFixtures.LOCAL_PINCODE);

        // Relative rank, not hits().get(0): the index is shared across this
        // whole test run (see class doc comment) and may hold other
        // Money Plant documents from other test classes - this only
        // claims what the scenario actually means, that nearSeller
        // outranks farSeller, regardless of who else is in the list.
        assertThat(rankOf(results, nearSeller)).isLessThan(rankOf(results, farSeller));
    }

    @Test
    void outOfStock_ranksLower_butRemainsDiscoverable() throws Exception {
        UUID sellerA = seedSeller("Ranking InStock Nursery " + UUID.randomUUID(), AllocationFixtures.LOCAL_PINCODE);
        UUID sellerB = seedSeller("Ranking OutOfStock Nursery " + UUID.randomUUID(), AllocationFixtures.LOCAL_PINCODE);
        indexListing(sellerA, moneyPlantId, "349.00", 5);
        UUID outId = indexListing(sellerB, moneyPlantId, "299.00", 0);

        var results = search("money plant", AllocationFixtures.LOCAL_PINCODE);

        assertThat(results.hits()).anyMatch(h -> h.listingId().equals(outId));
        assertThat(results.hits().get(0).listingId()).isNotEqualTo(outId);
    }

    @Test
    void synonyms_matchAcrossVernacularNames() throws Exception {
        UUID seller = seedSeller("Ranking Synonym Nursery " + UUID.randomUUID(), AllocationFixtures.LOCAL_PINCODE);
        UUID id = indexListing(seller, moneyPlantId, "349.00", 5);

        assertThat(search("pothos", AllocationFixtures.LOCAL_PINCODE).hits())
            .anyMatch(h -> h.listingId().equals(id));
        assertThat(search("devils ivy", AllocationFixtures.LOCAL_PINCODE).hits())
            .anyMatch(h -> h.listingId().equals(id));
    }

    @Test
    void misspelling_stillMatches() throws Exception {
        UUID seller = seedSeller("Ranking Misspelling Nursery " + UUID.randomUUID(), AllocationFixtures.LOCAL_PINCODE);
        UUID id = indexListing(seller, moneyPlantId, "349.00", 5);

        assertThat(search("money plnat", AllocationFixtures.LOCAL_PINCODE).hits())
            .anyMatch(h -> h.listingId().equals(id));
    }

    @Test
    void suspendedSellerListings_neverAppear() throws Exception {
        UUID suspendedSeller = AllocationFixtures.seedSeller(jdbc,
            "Ranking Suspended Nursery " + UUID.randomUUID(), "SUSPENDED", AllocationFixtures.LOCAL_PINCODE);
        UUID id = indexListing(suspendedSeller, moneyPlantId, "199.00", 5);

        assertThat(search("money plant", AllocationFixtures.LOCAL_PINCODE).hits())
            .noneMatch(h -> h.listingId().equals(id));
    }

    @Test
    void unserviceableOrigin_isFilteredOut() throws Exception {
        UUID remoteSeller = seedSeller("Ranking Remote Nursery " + UUID.randomUUID(), AllocationFixtures.REMOTE_PINCODE);
        // Boston Fern's real max_transit_days is 2 (V8); REMOTE -> REMOTE
        // is a real 9-day transit (V20) - far past what a fern survives.
        indexListing(remoteSeller, bostonFernId, "299.00", 5);

        assertThat(search("boston fern", AllocationFixtures.REMOTE_PINCODE).hits()).isEmpty();
    }
}
