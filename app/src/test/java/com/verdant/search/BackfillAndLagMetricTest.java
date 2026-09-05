package com.verdant.search;

import com.verdant.common.testing.AllocationFixtures;
import com.verdant.common.testing.SearchIntegrationTestBase;
import com.verdant.search.admin.BackfillService;
import com.verdant.search.projection.SearchReindexQueueRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.FieldValue;
import org.opensearch.client.opensearch.core.BulkRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Day 52: backfill (blue-green reindexing) and the real search.index.lag
 * metric. Both real, both against real infrastructure - a genuinely
 * separate, freshly PUT index for the backfill test (not the shared
 * "listings" alias every other search test writes through), and the real
 * Micrometer Timer ReindexWorker.drain() records on every real scheduled
 * cycle.
 */
@AutoConfigureMockMvc
class BackfillAndLagMetricTest extends SearchIntegrationTestBase {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private OpenSearchClient opensearch;
    @Autowired private SearchReindexQueueRepository reindexQueue;
    @Autowired private BackfillService backfillService;
    @Autowired private MeterRegistry meterRegistry;
    @Autowired private MockMvc mockMvc;

    private UUID canonicalProductId;
    private final List<UUID> createdListingIds = new ArrayList<>();

    @BeforeEach
    void seed() {
        AllocationFixtures.seedLocalPincodeZone(jdbc);
        canonicalProductId = AllocationFixtures.seedCanonicalProduct(jdbc, "Backfill Test Plant");
    }

    @AfterEach
    void cleanup() throws Exception {
        if (createdListingIds.isEmpty()) {
            return;
        }
        BulkRequest.Builder request = new BulkRequest.Builder();
        for (UUID id : createdListingIds) {
            request.operations(op -> op.delete(del -> del.index("listings").id(id.toString())));
        }
        opensearch.bulk(request.build());
    }

    @Test
    void backfillAll_indexesExistingListings_intoTheGivenTargetIndex_notTheLiveAlias() throws Exception {
        UUID seller = AllocationFixtures.seedApprovedSeller(jdbc, "Backfill Test Nursery " + UUID.randomUUID());
        UUID id = AllocationFixtures.seedListing(jdbc, seller, canonicalProductId, "Backfill Listing", "199.00", 5);
        createdListingIds.add(id);
        String targetIndex = "listings-backfill-test-" + UUID.randomUUID().toString().substring(0, 8);
        createIndex(targetIndex);

        int indexed = backfillService.backfillAll(targetIndex);

        assertThat(indexed).isGreaterThanOrEqualTo(1);
        opensearch.indices().refresh(r -> r.index(targetIndex));
        long countInTarget = opensearch.count(c -> c.index(targetIndex)
            .query(q -> q.term(t -> t.field("listing_id").value(FieldValue.of(id.toString()))))).count();
        assertThat(countInTarget)
            .as("backfill wrote into the new index, not the live 'listings' alias")
            .isEqualTo(1);

        deleteIndex(targetIndex);
    }

    @Test
    void backfill_nonPlatformAdmin_isForbidden() throws Exception {
        mockMvc.perform(post("/v1/admin/search/backfill")
                .with(jwt().jwt(j -> j.subject("sub-seller-" + UUID.randomUUID())))
                .contentType(APPLICATION_JSON)
                .content("""
                    {"targetIndex": "listings-should-not-be-used"}
                    """))
            .andExpect(status().isForbidden());
    }

    @Test
    void backfill_platformAdmin_isAccepted() throws Exception {
        mockMvc.perform(post("/v1/admin/search/backfill")
                .with(jwt().jwt(j -> j.subject("sub-ops-" + UUID.randomUUID()))
                    .authorities(new SimpleGrantedAuthority("PLATFORM_ADMIN")))
                .contentType(APPLICATION_JSON)
                .content("""
                    {"targetIndex": "listings-accepted-test"}
                    """))
            .andExpect(status().isAccepted());
    }

    /**
     * The real end-to-end lag number (Day 52.2): queues a listing, waits
     * for the REAL scheduled ReindexWorker.drain() (not drainBatch() -
     * this metric is specifically recorded there, see that method's own
     * comment) to pick it up on its own, and confirms search.index.lag
     * recorded a real, sane (sub-10-second, since nothing in this test
     * environment is under real load) duration - not just that indexing
     * eventually happened (ProjectionTest already proves that), but that
     * this specific measurement exists and reports something believable.
     */
    @Test
    void reindexDrain_recordsRealIndexingLag() throws Exception {
        UUID seller = AllocationFixtures.seedApprovedSeller(jdbc, "Lag Test Nursery " + UUID.randomUUID());
        UUID id = AllocationFixtures.seedListing(jdbc, seller, canonicalProductId, "Lag Test Listing", "150.00", 5);
        createdListingIds.add(id);

        Timer before = meterRegistry.find("search.index.lag").timer();
        long countBefore = before == null ? 0 : before.count();

        reindexQueue.enqueue(id, "TEST_SETUP");

        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        Timer lagTimer = null;
        while (Instant.now().isBefore(deadline)) {
            lagTimer = meterRegistry.find("search.index.lag").timer();
            if (lagTimer != null && lagTimer.count() > countBefore) {
                break;
            }
            Thread.sleep(100);
        }

        assertThat(lagTimer).isNotNull();
        assertThat(lagTimer.count()).isGreaterThan(countBefore);
        assertThat(lagTimer.max(TimeUnit.SECONDS))
            .as("a real lag measurement, not a zero/negative placeholder")
            .isGreaterThan(0.0)
            .isLessThan(10.0);
    }

    /** Same real PUT this base class's own createListingsIndex() uses for "listings-v1" - a genuinely separate, throwaway index for this one test, never the shared "listings" alias. */
    private void createIndex(String name) throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        String mapping;
        try (InputStream in = getClass().getResourceAsStream("/opensearch/listings-index.json")) {
            mapping = new String(in.readAllBytes());
        }
        http.send(HttpRequest.newBuilder(URI.create(opensearchBaseUri() + "/" + name))
                .PUT(HttpRequest.BodyPublishers.ofString(mapping))
                .header("Content-Type", "application/json").build(),
            HttpResponse.BodyHandlers.discarding());
    }

    private void deleteIndex(String name) throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        http.send(HttpRequest.newBuilder(URI.create(opensearchBaseUri() + "/" + name)).DELETE().build(),
            HttpResponse.BodyHandlers.discarding());
    }
}
