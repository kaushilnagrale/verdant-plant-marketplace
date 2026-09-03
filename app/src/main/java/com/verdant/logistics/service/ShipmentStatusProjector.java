package com.verdant.logistics.service;

import com.verdant.logistics.domain.ShipmentStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

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
