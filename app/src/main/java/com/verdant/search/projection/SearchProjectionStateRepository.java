package com.verdant.search.projection;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * Bookkeeping only - nothing in this project's own 5-day scope reads this
 * back (the runbook's own "what breaks this week" table mentions a
 * nightly full-reindex drift check as the eventual consumer, but never
 * builds one in the days it actually shows). Recorded anyway, the same
 * reasoning as is_cash_on_delivery (Week 8): real production knowledge
 * worth encoding correctly even for a column nothing currently queries.
 *
 * last_event_id doesn't track a real outbox event id - doing that
 * properly would mean changing OutboxWriter's shared payload envelope for
 * every future producer, not just this one, which is out of scope here.
 * Both columns are simply how many times this listing has been
 * (re)indexed - an honest simplification, not a fabricated identifier.
 */
@Repository
public class SearchProjectionStateRepository {

    private final JdbcTemplate jdbc;

    public SearchProjectionStateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void recordIndexed(UUID listingId) {
        jdbc.update("""
            INSERT INTO search_projection_state (listing_id, last_event_id, version)
            VALUES (?, 1, 1)
            ON CONFLICT (listing_id) DO UPDATE
                SET last_event_id = search_projection_state.version + 1,
                    version = search_projection_state.version + 1,
                    indexed_at = now()
            """, listingId);
    }
}
