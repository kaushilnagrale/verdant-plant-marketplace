package com.verdant.search.projection;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * listing_id is the primary key, not a surrogate id (V33) - deliberate
 * coalescing: five rapid changes to one listing collapse into one queued
 * reindex, never five wasteful round trips.
 *
 * Departs from OutboxRepository's own claim shape on purpose: OutboxDrainer
 * holds one open transaction across its whole claim-then-publish cycle,
 * which is fine for a single Kafka send but is exactly the anti-pattern
 * this project has twice already avoided (CheckoutService, Day 23;
 * ReconciliationJob, Day 46) when the "publish" step is a real external
 * HTTP call - OpenSearch's bulk index here. claimBatch() is instead a
 * single atomic statement (SKIP LOCKED selection nested inside its own
 * DELETE ... RETURNING) that claims AND removes rows in one round trip,
 * needing no explicit transaction of its own - the row is simply gone the
 * instant this call returns, success or not. A batch that fails to index
 * is put back via scheduleRetry(), a separate, equally short statement -
 * so the real OpenSearch call in between never holds a lock or a
 * transaction open at all.
 */
@Repository
public class SearchReindexQueueRepository {

    private final JdbcTemplate jdbc;

    public SearchReindexQueueRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void enqueue(UUID listingId, String reason) {
        jdbc.update("""
            INSERT INTO search_reindex_queue (listing_id, reason) VALUES (?, ?)
            ON CONFLICT (listing_id) DO UPDATE SET reason = EXCLUDED.reason, queued_at = now()
            """, listingId, reason);
    }

    public List<UUID> claimBatch(int limit) {
        return jdbc.query("""
            DELETE FROM search_reindex_queue
             WHERE listing_id IN (
                SELECT listing_id FROM search_reindex_queue
                 ORDER BY queued_at
                 LIMIT ?
                 FOR UPDATE SKIP LOCKED
             )
            RETURNING listing_id
            """, (rs, n) -> (UUID) rs.getObject("listing_id"), limit);
    }

    /** Puts a listing back on the queue after a failed index attempt, with the attempt count preserved rather than reset. */
    public void scheduleRetry(UUID listingId, String reason) {
        jdbc.update("""
            INSERT INTO search_reindex_queue (listing_id, reason, attempts) VALUES (?, ?, 1)
            ON CONFLICT (listing_id) DO UPDATE
                SET reason = EXCLUDED.reason, queued_at = now(), attempts = search_reindex_queue.attempts + 1
            """, listingId, reason);
    }

    public int pendingCount() {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM search_reindex_queue", Integer.class);
        return count == null ? 0 : count;
    }
}
