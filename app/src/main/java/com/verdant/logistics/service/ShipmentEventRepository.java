package com.verdant.logistics.service;

import com.verdant.logistics.web.TrackingEvent;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ShipmentEventRepository {

    private final JdbcTemplate jdbc;

    public ShipmentEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<UUID> shipmentIdForAwb(String awb) {
        List<UUID> ids = jdbc.queryForList("SELECT id FROM shipments WHERE awb_number = ?", UUID.class, awb);
        return ids.isEmpty() ? Optional.empty() : Optional.of(ids.get(0));
    }

    /** UNIQUE (shipment_id, provider_event_id) is what actually enforces dedup - throws on a real duplicate, not swallowed here. */
    public void insert(UUID shipmentId, TrackingEvent event) throws DataIntegrityViolationException {
        jdbc.update("""
            INSERT INTO shipment_events (shipment_id, provider_event_id, status, location, remark, occurred_at)
            VALUES (?, ?, ?, ?, ?, ?)
            """, shipmentId, event.providerEventId(), event.status(), event.location(), event.remark(),
            Timestamp.from(event.occurredAt()));
    }

    public long countByShipmentId(UUID shipmentId) {
        return jdbc.queryForObject(
            "SELECT count(*) FROM shipment_events WHERE shipment_id = ?", Long.class, shipmentId);
    }
}
