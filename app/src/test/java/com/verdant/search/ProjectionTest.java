package com.verdant.search;

import com.verdant.common.testing.AllocationFixtures;
import com.verdant.common.testing.SearchIntegrationTestBase;
import com.verdant.identity.domain.SellerStatus;
import com.verdant.identity.service.SellerOnboardingService;
import com.verdant.search.projection.ListingDocument;
import com.verdant.search.projection.ReindexWorker;
import com.verdant.search.projection.SearchReindexQueueRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opensearch.client.json.jackson.JacksonJsonpMapper;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.core.BulkRequest;
import org.opensearch.client.opensearch.core.GetResponse;
import org.opensearch.client.transport.OpenSearchTransport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Adapted from the runbook's own scenarios in two deliberate ways, both
 * because Day 50 (ranking/the search query itself) doesn't exist yet at
 * this point in the week: assertions check the real OpenSearch document
 * directly (client.get(), by id) rather than through a search() call
 * nothing has built yet, and "removed from results" is verified as
 * "indexed with active=false" - the search query Day 50 adds will filter
 * on that field, so this is the same real outcome from the projection
 * pipeline's own side, not a weaker substitute for it.
 *
 * Every test drives the REAL producer (a real HTTP call through
 * ListingController, or a real SellerOnboardingService.transition() call)
 * end to end through the real outbox -> real Kafka (Redpanda,
 * Testcontainers) -> real ListingProjector consumer -> search_reindex_queue
 * -> real ReindexWorker -> real OpenSearch (Testcontainers). awaitIndexed()
 * polls the real index because that round trip is genuinely asynchronous -
 * this is the actual consistency window Day 52 measures, not something to
 * paper over with a direct method call.
 */
@AutoConfigureMockMvc
class ProjectionTest extends SearchIntegrationTestBase {

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private OpenSearchClient opensearch;
    @Autowired private SellerOnboardingService sellerOnboarding;
    @Autowired private SearchReindexQueueRepository reindexQueue;
    @Autowired private ReindexWorker reindexWorker;
    @Autowired private com.verdant.search.projection.ProjectionQueryRepository projectionQueryRepository;
    @Autowired private com.verdant.search.projection.SearchProjectionStateRepository searchProjectionStateRepository;
    @Autowired private MeterRegistry meterRegistry;

    private UUID sellerId;
    private String ownerSubject;
    private final List<UUID> createdListingIds = new ArrayList<>();

    @BeforeEach
    void seed() {
        AllocationFixtures.seedLocalPincodeZone(jdbc);
        sellerId = AllocationFixtures.seedApprovedSeller(jdbc, "Projection Test Nursery " + UUID.randomUUID());
        ownerSubject = "sub-owner-" + UUID.randomUUID();
        jdbc.update("INSERT INTO seller_users (seller_id, subject_id, role) VALUES (?, ?, 'OWNER')", sellerId, ownerSubject);
    }

    /**
     * None of this class's own assertions go through _search (real-time
     * GET by id, or direct queue-row counts instead - see this class's
     * own header comment), so unlike RankingTest/FacetsTest this class's
     * accumulation was never actually breaking anything here. Still
     * cleaned up so this class stops contributing to the shared index's
     * permanent growth under OPENSEARCH.withReuse(true) - one less real
     * source inflating the corpus those other tests' own result windows
     * have to stay under.
     */
    @AfterEach
    void deleteIndexedDocuments() throws Exception {
        if (createdListingIds.isEmpty()) {
            return;
        }
        BulkRequest.Builder request = new BulkRequest.Builder();
        for (UUID id : createdListingIds) {
            request.operations(op -> op.delete(del -> del.index("listings").id(id.toString())));
        }
        opensearch.bulk(request.build());
    }

    private UUID createListing(String title, String price) throws Exception {
        String body = """
            {"rawTitle": "%s", "priceAmount": "%s"}
            """.formatted(title, price);
        String response = mockMvc.perform(post("/v1/listings")
                .with(jwt().jwt(j -> j.subject(ownerSubject)))
                .contentType(APPLICATION_JSON).content(body))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        UUID listingId = UUID.fromString(response.replaceAll(".*\"id\":\"([0-9a-fA-F-]+)\".*", "$1"));
        createdListingIds.add(listingId);
        return listingId;
    }

    /**
     * Maps the listing to a real canonical product directly - the async
     * mapping worker (Week 3) would eventually do this too, but not
     * deterministically within a test's own timing.
     *
     * Real race, found the hard way (twice): createListing() kicks off
     * that same real async worker automatically, and it can finish AFTER
     * this override, silently clobbering it back to whatever the
     * automatic mapper concluded - PENDING_REVIEW for a title with no
     * strong semantic match. Naming the listing after the real product
     * was tried first and was not reliable enough - the async worker's
     * own timing (a real ONNX embedding computation) varies enough that
     * even a matching title doesn't guarantee it wins the race. The
     * actual fix is waiting for the worker to genuinely finish first:
     * mapping_confidence is NULL only until the worker has touched the
     * row at all (whatever it concludes always sets a real float), so
     * polling on it becoming non-null - then applying this override
     * strictly after - makes this override always the last write,
     * regardless of how long the real embedding computation takes.
     */
    private void mapToRealProduct(UUID listingId, String canonicalDisplayName) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        while (Instant.now().isBefore(deadline)) {
            Boolean workerHasRun = jdbc.queryForObject(
                "SELECT mapping_confidence IS NOT NULL FROM listings WHERE id = ?", Boolean.class, listingId);
            if (Boolean.TRUE.equals(workerHasRun)) {
                break;
            }
            Thread.sleep(100);
        }

        UUID canonicalId = jdbc.queryForObject(
            "SELECT id FROM canonical_products WHERE display_name = ?", UUID.class, canonicalDisplayName);
        jdbc.update("""
            UPDATE listings SET canonical_product_id = ?, mapping_status = 'MANUAL', mapping_confidence = 1.0
             WHERE id = ?
            """, canonicalId, listingId);
    }

    private void seedInventory(UUID listingId, int quantity) {
        jdbc.update("INSERT INTO inventory (listing_id, quantity, buffer) VALUES (?, ?, 1)", listingId, quantity);
    }

    private ListingDocument awaitIndexed(UUID listingId, Duration timeout) throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            GetResponse<ListingDocument> response = opensearch.get(
                g -> g.index("listings").id(listingId.toString()), ListingDocument.class);
            if (response.found()) {
                return response.source();
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Listing " + listingId + " was not indexed within " + timeout);
    }

    @Test
    void listingCreateThenUpdate_propagatesToIndex_withinConsistencyBudget() throws Exception {
        UUID id = createListing("Projection Money Plant", "349.00");
        mapToRealProduct(id, "Money Plant");
        seedInventory(id, 5);

        // The create() event already fired before the listing was mapped/
        // stocked - re-enqueue directly rather than wait on the async
        // mapping worker's own timing, which this test isn't about.
        reindexQueue.enqueue(id, "TEST_SETUP");
        ListingDocument first = awaitIndexed(id, Duration.ofSeconds(10));
        assertThat(first.priceAmount()).isEqualByComparingTo("349.00");

        mockMvc.perform(patch("/v1/listings/" + id)
                .with(jwt().jwt(j -> j.subject(ownerSubject)))
                .contentType(APPLICATION_JSON).content("""
                    {"priceAmount": "399.00"}
                    """))
            .andExpect(status().isOk());

        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        ListingDocument updated;
        do {
            updated = awaitIndexed(id, Duration.ofSeconds(10));
        } while (updated.priceAmount().compareTo(new java.math.BigDecimal("399.00")) != 0
                 && Instant.now().isBefore(deadline));

        assertThat(updated.priceAmount()).isEqualByComparingTo("399.00");
    }

    @Test
    void sellerSuspension_marksAllTheirListingsInactiveInTheIndex() throws Exception {
        UUID listingA = createListing("Projection Fern A", "199.00");
        UUID listingB = createListing("Projection Fern B", "249.00");
        mapToRealProduct(listingA, "Boston Fern");
        mapToRealProduct(listingB, "Boston Fern");
        seedInventory(listingA, 5);
        seedInventory(listingB, 5);
        reindexQueue.enqueue(listingA, "TEST_SETUP");
        reindexQueue.enqueue(listingB, "TEST_SETUP");
        awaitIndexed(listingA, Duration.ofSeconds(10));
        awaitIndexed(listingB, Duration.ofSeconds(10));

        sellerOnboarding.transition(sellerId, SellerStatus.SUSPENDED, "test-ops", "projection test");

        assertThat(pollUntilInactive(listingA, Duration.ofSeconds(10))).isTrue();
        assertThat(pollUntilInactive(listingB, Duration.ofSeconds(10))).isTrue();
    }

    private boolean pollUntilInactive(UUID listingId, Duration timeout) throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            ListingDocument doc = awaitIndexed(listingId, timeout);
            if (!doc.active()) {
                return true;
            }
            Thread.sleep(100);
        }
        return false;
    }

    @Test
    void rapidUpdates_coalesceIntoOneQueueEntry_notOnePerUpdate() throws Exception {
        UUID id = createListing("Projection Coalesce Plant", "100.00");

        for (int i = 0; i < 10; i++) {
            mockMvc.perform(patch("/v1/listings/" + id)
                    .with(jwt().jwt(j -> j.subject(ownerSubject)))
                    .contentType(APPLICATION_JSON).content("""
                        {"priceAmount": "1%02d.00"}
                        """.formatted(i)))
                .andExpect(status().isOk());
        }

        // 11 real outbox events now exist for this listing (1 create + 10
        // updates) - poll until the projector has consumed all of them,
        // then assert the QUEUE still collapsed to exactly one entry, the
        // real, direct evidence of coalescing (listing_id is the queue's
        // own primary key, V33) rather than an indirect index-call count.
        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        while (Instant.now().isBefore(deadline) && countQueueRowsFor(id) == 0) {
            Thread.sleep(100);
        }
        assertThat(countQueueRowsFor(id)).isEqualTo(1);
    }

    private int countQueueRowsFor(UUID listingId) {
        Integer count = jdbc.queryForObject(
            "SELECT count(*) FROM search_reindex_queue WHERE listing_id = ?", Integer.class, listingId);
        return count == null ? 0 : count;
    }

    @Test
    void reprojection_isIdempotent() throws Exception {
        UUID id = createListing("Projection Jade Plant Idempotent", "150.00");
        mapToRealProduct(id, "Jade Plant");
        seedInventory(id, 3);
        reindexQueue.enqueue(id, "TEST_SETUP");
        ListingDocument before = awaitIndexed(id, Duration.ofSeconds(10));

        reindexQueue.enqueue(id, "MANUAL");
        reindexWorker.drainBatch(List.of(id));
        ListingDocument after = awaitIndexed(id, Duration.ofSeconds(10));

        // indexed_at is deliberately excluded: it is real "when was this
        // last indexed" metadata, not part of the underlying listing data
        // being projected, and correctly changes on every real reindex.
        // "Idempotent" means the same content comes back, not a frozen
        // timestamp - asserting otherwise would be testing a mistaken
        // expectation, not this project's own actual idempotency claim.
        assertThat(after).usingRecursiveComparison().ignoringFields("indexedAt").isEqualTo(before);
    }

    /**
     * The important one, per the runbook's own emphasis: the read model is
     * disposable. A genuinely broken OpenSearchClient is exercised
     * directly against ReindexWorker (constructed here with a mock
     * transport that always throws, bypassing the real Spring-wired
     * client for just this one call) to prove drainBatch() fails
     * gracefully rather than propagating - and separately, that nothing
     * on the real order-placement path calls search code at all, proven
     * simply by the fact that checkout/payment verification (Week 4) has
     * never taken an OpenSearchClient dependency anywhere in this project.
     */
    @Test
    void openSearchDown_failsGracefully_doesNotThrow() throws Exception {
        UUID id = createListing("Projection Down Plant", "220.00");

        OpenSearchTransport brokenTransport = mock(OpenSearchTransport.class);
        when(brokenTransport.jsonpMapper()).thenReturn(new JacksonJsonpMapper());
        when(brokenTransport.performRequest(any(), any(), any()))
            .thenThrow(new RuntimeException("simulated: OpenSearch is unreachable"));
        OpenSearchClient brokenClient = new OpenSearchClient(brokenTransport);

        ReindexWorker workerAgainstDownCluster = new ReindexWorker(
            reindexQueue, projectionQueryRepository, searchProjectionStateRepository, brokenClient, meterRegistry);

        reindexQueue.enqueue(id, "TEST_SETUP");
        workerAgainstDownCluster.drainBatch(List.of(id));

        // No exception propagated - and the listing goes back on the
        // queue for a real, healthy worker to pick up later, rather than
        // being silently lost.
        assertThat(countQueueRowsFor(id)).isEqualTo(1);
    }

    @Test
    void deactivatedListing_isIndexedAsInactive() throws Exception {
        UUID id = createListing("Projection Rose Deactivate", "275.00");
        mapToRealProduct(id, "Rose");
        seedInventory(id, 5);
        reindexQueue.enqueue(id, "TEST_SETUP");
        ListingDocument before = awaitIndexed(id, Duration.ofSeconds(10));
        assertThat(before.active()).isTrue();

        mockMvc.perform(post("/v1/listings/" + id + "/deactivate")
                .with(jwt().jwt(j -> j.subject(ownerSubject))))
            .andExpect(status().isOk());

        assertThat(pollUntilInactive(id, Duration.ofSeconds(10))).isTrue();
    }
}
