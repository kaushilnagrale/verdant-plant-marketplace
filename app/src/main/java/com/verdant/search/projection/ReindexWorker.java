package com.verdant.search.projection;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.core.BulkRequest;
import org.opensearch.client.opensearch.core.BulkResponse;
import org.opensearch.client.opensearch.core.bulk.BulkResponseItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * One SQL query builds every document in the batch (ProjectionQueryRepository)
 * - the N+1 the runbook itself calls out as the classic mistake here,
 * avoided from the start.
 *
 * Real gap in the runbook's own sketch, closed here: buildDocuments()'s
 * INNER JOIN to canonical_products (deliberate - see that class's own doc
 * comment) means a claimed listing id can legitimately come back with NO
 * document at all - unmapped, or genuinely gone. Left indexed, such a
 * document would be a permanent ghost nothing could ever update again.
 * Explicitly deleted from the index instead of silently dropped.
 *
 * A DEACTIVATED listing is handled differently, and deliberately not by
 * deleting it: it still joins successfully (canonical_product_id is
 * untouched by deactivation) and is indexed normally with active=false -
 * the search query (Day 50) filters on active, so it stops appearing in
 * results without needing a hard delete. Simpler, and the runbook's own
 * "removed from index" test is really asserting "removed from results",
 * which this satisfies identically.
 */
@Component
public class ReindexWorker {

    private static final Logger log = LoggerFactory.getLogger(ReindexWorker.class);
    private static final String INDEX = "listings";

    private final SearchReindexQueueRepository reindexQueue;
    private final ProjectionQueryRepository projectionQuery;
    private final SearchProjectionStateRepository projectionState;
    private final OpenSearchClient opensearch;
    private final Timer indexLagTimer;

    public ReindexWorker(SearchReindexQueueRepository reindexQueue, ProjectionQueryRepository projectionQuery,
                          SearchProjectionStateRepository projectionState, OpenSearchClient opensearch,
                          MeterRegistry meterRegistry) {
        this.reindexQueue = reindexQueue;
        this.projectionQuery = projectionQuery;
        this.projectionState = projectionState;
        this.opensearch = opensearch;
        // Real end-to-end lag (Day 52): queued_at (the moment the write
        // model changed and asked to be reindexed) to right now, measured
        // once the bulk index call below has actually completed - not at
        // claim time, which would understate it by exactly the real
        // OpenSearch round trip this metric exists to capture. This is
        // "indexed" in the same real-time-GET sense ProjectionTest's own
        // awaitIndexed() already uses, not "visible to _search" (which
        // adds OpenSearch's own separate refresh-interval delay on top -
        // a different, additional number, not this one).
        this.indexLagTimer = Timer.builder("search.index.lag")
            .description("Time from a listing change being queued for reindex to actually landing in OpenSearch")
            .publishPercentiles(0.5, 0.95, 0.99)
            .register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${verdant.search.reindex-interval-ms:500}")
    public void drain() {
        List<SearchReindexQueueRepository.QueuedItem> claimed = reindexQueue.claimBatchWithQueuedAt(200);
        if (claimed.isEmpty()) {
            return;
        }
        List<UUID> batch = claimed.stream().map(SearchReindexQueueRepository.QueuedItem::listingId).toList();
        drainBatch(batch);

        Instant now = Instant.now();
        claimed.forEach(item -> indexLagTimer.record(Duration.between(item.queuedAt(), now)));
    }

    /** Exposed for tests to call directly and deterministically - same reasoning as every other @Scheduled job in this project (ReconciliationJob.reconcile(), PayoutCycleBuildJob.buildCyclesFor(), etc.). */
    public void drainBatch(List<UUID> batch) {
        List<ListingDocument> docs = projectionQuery.buildDocuments(batch);

        Set<UUID> found = docs.stream().map(ListingDocument::listingId).collect(Collectors.toSet());
        List<UUID> noLongerIndexable = batch.stream().filter(id -> !found.contains(id)).toList();

        try {
            BulkResponse response = bulkWrite(docs, noLongerIndexable);

            Set<UUID> failedIds = new HashSet<>();
            if (response.errors()) {
                for (BulkResponseItem item : response.items()) {
                    if (item.error() != null) {
                        UUID failedId = UUID.fromString(item.id());
                        failedIds.add(failedId);
                        log.error("OpenSearch bulk operation failed for listing {}: {}", failedId, item.error().reason());
                        reindexQueue.scheduleRetry(failedId, "bulk index error: " + item.error().reason());
                    }
                }
            }

            for (ListingDocument doc : docs) {
                if (!failedIds.contains(doc.listingId())) {
                    projectionState.recordIndexed(doc.listingId());
                }
            }
        } catch (Exception e) {
            log.error("Bulk index failed for {} listing(s)", batch.size(), e);
            batch.forEach(id -> reindexQueue.scheduleRetry(id, "bulk index exception: " + e.getMessage()));
        }
    }

    private BulkResponse bulkWrite(List<ListingDocument> docs, List<UUID> deletions) throws Exception {
        return bulkWriteInto(INDEX, docs, deletions);
    }

    private BulkResponse bulkWriteInto(String targetIndex, List<ListingDocument> docs, List<UUID> deletions) throws Exception {
        BulkRequest.Builder request = new BulkRequest.Builder();

        for (ListingDocument doc : docs) {
            request.operations(op -> op.index(idx -> idx
                .index(targetIndex)
                .id(doc.listingId().toString())
                .document(doc)));
        }
        for (UUID id : deletions) {
            request.operations(op -> op.delete(del -> del.index(targetIndex).id(id.toString())));
        }

        return opensearch.bulk(request.build());
    }

    /**
     * Backfill into an explicitly-named index (Day 52), never the
     * "listings" alias this class otherwise always writes through - the
     * whole point of blue-green reindexing is that a brand new index can
     * be built up fully, verified, and only THEN swapped in via an alias
     * flip (a separate, ops-level curl call - see scripts/reindex-all.sh),
     * so a mapping change never causes downtime on the live "listings"
     * alias while it's being rebuilt. No delete-of-missing branch here
     * unlike drainBatch() above: a fresh target index starts empty, so
     * there is nothing stale in it yet to remove.
     */
    public int reindexInto(String targetIndex, List<UUID> listingIds) {
        List<ListingDocument> docs = projectionQuery.buildDocuments(listingIds);
        if (docs.isEmpty()) {
            return 0;
        }
        try {
            BulkResponse response = bulkWriteInto(targetIndex, docs, List.of());
            if (response.errors()) {
                for (BulkResponseItem item : response.items()) {
                    if (item.error() != null) {
                        log.error("Backfill bulk index failed for listing {} into {}: {}",
                            item.id(), targetIndex, item.error().reason());
                    }
                }
            }
            return docs.size();
        } catch (Exception e) {
            log.error("Backfill bulk index failed for a batch of {} listing(s) into {}", listingIds.size(), targetIndex, e);
            throw new IllegalStateException("Backfill into " + targetIndex + " failed", e);
        }
    }
}
