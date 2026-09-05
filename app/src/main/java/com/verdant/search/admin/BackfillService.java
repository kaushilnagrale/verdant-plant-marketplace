package com.verdant.search.admin;

import com.verdant.search.projection.ReindexWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Full-catalog reindex into a brand new, explicitly-named index (Day 52) -
 * the write side of blue-green reindexing. Every listing, not just what's
 * on search_reindex_queue: a mapping change (the actual real reason to run
 * this) needs every existing document rebuilt under the new mapping, not
 * just whatever has changed recently.
 *
 * Keyset pagination (WHERE id > ? ORDER BY id), not OFFSET - a real
 * catalog backfill needs to stay correct and reasonably efficient however
 * large the table gets, and OFFSET pagination degrades linearly with
 * table size for no reason (every dev's first instinct here, and a common
 * real production mistake this project doesn't need to repeat).
 */
@Service
public class BackfillService {

    private static final Logger log = LoggerFactory.getLogger(BackfillService.class);
    private static final int BATCH_SIZE = 500;

    private final JdbcTemplate jdbc;
    private final ReindexWorker reindexWorker;

    public BackfillService(JdbcTemplate jdbc, ReindexWorker reindexWorker) {
        this.jdbc = jdbc;
        this.reindexWorker = reindexWorker;
    }

    /** The real controller entry point - fire-and-forget, same @Async convention as ListingMappingWorker (Week 3). A full-catalog backfill can genuinely take a while; the caller polls document counts to know when it's done (see scripts/reindex-all.sh), not this call's own return. */
    @Async
    public void backfillAllAsync(String targetIndex) {
        backfillAll(targetIndex);
    }

    /** Directly callable for tests - same reasoning as every other @Async/@Scheduled job in this project (PayoutCycleBuildJob.buildCyclesFor(), ReconciliationJob.reconcile(), etc.) that also exposes a synchronous entry point rather than forcing tests to race the real async/scheduled trigger. */
    public int backfillAll(String targetIndex) {
        int total = 0;
        UUID lastId = null;
        List<UUID> batch;
        do {
            batch = nextBatch(lastId);
            if (!batch.isEmpty()) {
                total += reindexWorker.reindexInto(targetIndex, batch);
                lastId = batch.get(batch.size() - 1);
            }
        } while (batch.size() == BATCH_SIZE);

        log.info("Backfill into {} complete: {} listing(s) indexed", targetIndex, total);
        return total;
    }

    private List<UUID> nextBatch(UUID afterId) {
        if (afterId == null) {
            return jdbc.queryForList("SELECT id FROM listings ORDER BY id LIMIT ?", UUID.class, BATCH_SIZE);
        }
        return jdbc.queryForList(
            "SELECT id FROM listings WHERE id > ? ORDER BY id LIMIT ?", UUID.class, afterId, BATCH_SIZE);
    }
}
