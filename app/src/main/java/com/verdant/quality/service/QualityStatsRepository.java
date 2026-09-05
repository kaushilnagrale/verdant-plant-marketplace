package com.verdant.quality.service;

import com.verdant.quality.domain.QualityStats;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Two different real sources feed one stats row, deliberately not just
 * one. totalOrders and totalDelivered are ground truth, read straight
 * from orders/order_items/shipments - always correct regardless of
 * whether anything ever explicitly recorded them as a "quality event".
 * Every other count comes from seller_quality_events, the audited signal
 * log this feature actually adds: totalDispatches is deliberately
 * DISPATCH_ON_TIME + DISPATCH_LATE (not a separate ground-truth dispatch
 * count) so it's self-consistent with onTimeDispatches by construction,
 * same reasoning for totalOpsRequests/fastResponses.
 *
 * Real, honestly-open gap (see QualityScorer's own class comment):
 * sellerCancellations, totalOpsRequests and fastResponses will read 0 for
 * every seller until ORDER_CANCELLED_BY_SELLER and OPS_RESPONSE_FAST/SLOW
 * have a real producer wired somewhere - no such infrastructure (seller-
 * attributed cancellation, or any support-ticket/response-time tracking)
 * exists anywhere else in this project yet. That reads as "seller never
 * cancels" / "always responsive", which is optimistic-by-default rather
 * than wrong: Component's own zero-denominator convention already treats
 * "no data" as 1.0, exactly this case.
 */
@Repository
public class QualityStatsRepository {

    private final JdbcTemplate jdbc;

    public QualityStatsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public QualityStats statsForWindow(UUID sellerId, Duration window) {
        Timestamp cutoff = Timestamp.from(Instant.now().minus(window));

        Map<String, Object> row = jdbc.queryForMap("""
            SELECT
              (SELECT count(DISTINCT oi.order_id) FROM order_items oi JOIN orders o ON o.id = oi.order_id
                WHERE oi.seller_id = ? AND o.created_at >= ?) AS total_orders,
              (SELECT count(*) FROM seller_quality_events
                WHERE seller_id = ? AND event_type = 'ORDER_CANCELLED_BY_SELLER' AND occurred_at >= ?) AS seller_cancellations,
              (SELECT count(*) FROM seller_quality_events
                WHERE seller_id = ? AND event_type IN ('DISPATCH_ON_TIME','DISPATCH_LATE') AND occurred_at >= ?) AS total_dispatches,
              (SELECT count(*) FROM seller_quality_events
                WHERE seller_id = ? AND event_type = 'DISPATCH_ON_TIME' AND occurred_at >= ?) AS on_time_dispatches,
              (SELECT count(*) FROM shipments
                WHERE seller_id = ? AND status = 'DELIVERED' AND delivered_at >= ?) AS total_delivered,
              (SELECT count(*) FROM seller_quality_events
                WHERE seller_id = ? AND event_type = 'CLAIM_UPHELD' AND occurred_at >= ?) AS claims_upheld,
              (SELECT count(*) FROM seller_quality_events
                WHERE seller_id = ? AND event_type = 'RTO_RECEIVED' AND occurred_at >= ?) AS rto_count,
              (SELECT count(*) FROM seller_quality_events
                WHERE seller_id = ? AND event_type IN ('OPS_RESPONSE_FAST','OPS_RESPONSE_SLOW') AND occurred_at >= ?) AS total_ops_requests,
              (SELECT count(*) FROM seller_quality_events
                WHERE seller_id = ? AND event_type = 'OPS_RESPONSE_FAST' AND occurred_at >= ?) AS fast_responses
            """,
            sellerId, cutoff, sellerId, cutoff, sellerId, cutoff, sellerId, cutoff, sellerId, cutoff,
            sellerId, cutoff, sellerId, cutoff, sellerId, cutoff, sellerId, cutoff);

        return new QualityStats(
            ((Number) row.get("total_orders")).intValue(),
            ((Number) row.get("seller_cancellations")).intValue(),
            ((Number) row.get("total_dispatches")).intValue(),
            ((Number) row.get("on_time_dispatches")).intValue(),
            ((Number) row.get("total_delivered")).intValue(),
            ((Number) row.get("claims_upheld")).intValue(),
            ((Number) row.get("rto_count")).intValue(),
            ((Number) row.get("total_ops_requests")).intValue(),
            ((Number) row.get("fast_responses")).intValue());
    }
}
