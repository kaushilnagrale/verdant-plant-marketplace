package com.verdant.logistics.service;

import com.verdant.logistics.domain.ShipmentStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Projects from the WHOLE event log every time, not just the event that
 * just arrived - tracking events genuinely arrive out of order and
 * duplicated, and applying each one as it comes would flip a DELIVERED
 * shipment back to IN_TRANSIT the moment a late, reordered webhook shows
 * up. The furthest status ever seen (max ordinal) is what gets written.
 */
@Service
public class ShipmentStatusProjector {

    /** Matches PayoutEligibility's own claim window (Week 8) - a buyer has 72h after delivery to file a claim before an item becomes payout-eligible. */
    private static final Duration CLAIM_WINDOW = Duration.ofHours(72);

    private final JdbcTemplate jdbc;

    public ShipmentStatusProjector(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void project(UUID shipmentId) {
        List<String> rawStatuses = jdbc.queryForList(
            "SELECT status FROM shipment_events WHERE shipment_id = ? ORDER BY occurred_at", String.class, shipmentId);

        ShipmentStatus furthest = rawStatuses.stream()
            .map(this::parseOrNull)
            .filter(java.util.Objects::nonNull)
            .max(Comparator.comparingInt(Enum::ordinal))
            .orElse(null);

        if (furthest == null) {
            return; // nothing recognizable in the log yet - leave the shipment's status alone
        }

        jdbc.update("UPDATE shipments SET status = ? WHERE id = ?", furthest.name(), shipmentId);

        if (furthest == ShipmentStatus.DELIVERED) {
            recordDeliveryAndOpenClaimWindows(shipmentId);
        }
    }

    /**
     * Real, previously-invisible gap found while building Week 8's claims
     * feature: delivered_at existed as a column (Day 38) but nothing ever
     * set it - PayoutEligibility and the claims window both need it. Set
     * once (idempotent - a re-projection after a duplicate/late DELIVERED
     * event must not push the window forward again), from the earliest
     * DELIVERED event's own occurred_at rather than "now" - the webhook
     * telling us delivery happened can itself arrive late.
     */
    private void recordDeliveryAndOpenClaimWindows(UUID shipmentId) {
        int updated = jdbc.update(
            "UPDATE shipments SET delivered_at = ? WHERE id = ? AND delivered_at IS NULL",
            earliestDeliveredEventTime(shipmentId), shipmentId);
        if (updated == 0) {
            return; // already recorded - do not reopen claim windows on a duplicate/late event
        }

        Instant deliveredAt = jdbc.queryForObject(
            "SELECT delivered_at FROM shipments WHERE id = ?", Timestamp.class, shipmentId).toInstant();

        jdbc.update("""
            INSERT INTO claim_windows (order_item_id, opens_at, closes_at)
            SELECT oi.id, ?, ?
              FROM order_items oi WHERE oi.order_id = (SELECT order_id FROM shipments WHERE id = ?)
                                    AND oi.seller_id = (SELECT seller_id FROM shipments WHERE id = ?)
            ON CONFLICT (order_item_id) DO NOTHING
            """, Timestamp.from(deliveredAt), Timestamp.from(deliveredAt.plus(CLAIM_WINDOW)), shipmentId, shipmentId);
    }

    private Timestamp earliestDeliveredEventTime(UUID shipmentId) {
        return jdbc.queryForObject("""
            SELECT min(occurred_at) FROM shipment_events WHERE shipment_id = ? AND status = 'DELIVERED'
            """, Timestamp.class, shipmentId);
    }

    /** Tolerant of a vendor status string this project doesn't recognize yet, rather than failing the whole projection over one event. */
    private ShipmentStatus parseOrNull(String raw) {
        try {
            return ShipmentStatus.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
