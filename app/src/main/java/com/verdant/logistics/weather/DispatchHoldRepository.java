package com.verdant.logistics.weather;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class DispatchHoldRepository {

    private final JdbcTemplate jdbc;

    public DispatchHoldRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Records what the gate WOULD have done, without holding anything - shadow mode's whole point. */
    public void recordShadow(UUID shipmentId, HoldReason reason, String forecastSnapshot) {
        jdbc.update("""
            INSERT INTO dispatch_holds (shipment_id, reason, forecast_snapshot, max_hold_until, shadow)
            VALUES (?, ?, ?::jsonb, ?, TRUE)
            """, shipmentId, reason.name(), forecastSnapshot, java.sql.Timestamp.valueOf(LocalDate.now().plusDays(5).atStartOfDay()));
    }

    public void recordReal(UUID shipmentId, HoldReason reason, String forecastSnapshot, LocalDate maxHoldUntil) {
        jdbc.update("""
            INSERT INTO dispatch_holds (shipment_id, reason, forecast_snapshot, max_hold_until, shadow)
            VALUES (?, ?, ?::jsonb, ?, FALSE)
            """, shipmentId, reason.name(), forecastSnapshot, java.sql.Timestamp.valueOf(maxHoldUntil.atStartOfDay()));
    }

    public Optional<UUID> findShadowFor(UUID shipmentId) {
        List<UUID> ids = jdbc.queryForList(
            "SELECT id FROM dispatch_holds WHERE shipment_id = ? AND shadow = TRUE ORDER BY held_at DESC LIMIT 1",
            UUID.class, shipmentId);
        return ids.isEmpty() ? Optional.empty() : Optional.of(ids.get(0));
    }

    public List<UUID> findActive() {
        return jdbc.queryForList(
            "SELECT id FROM dispatch_holds WHERE released_at IS NULL AND shadow = FALSE", UUID.class);
    }
}
