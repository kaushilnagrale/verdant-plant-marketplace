package com.verdant.logistics.weather;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
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

    /**
     * Only inserts a new row if this shipment has no active (unreleased,
     * non-shadow) hold already - found while building HoldReleaseJob:
     * DispatchGate.evaluate() gets called again on every review pass, and
     * a naive unconditional INSERT would create a fresh dispatch_holds row
     * every single time the weather stays bad, instead of reusing the one
     * hold that's already open.
     */
    /** @return true if this call actually created a new hold (vs. an existing active hold already covering this shipment) - lets the caller notify only once, not on every re-evaluation. */
    public boolean recordReal(UUID shipmentId, HoldReason reason, String forecastSnapshot, LocalDate maxHoldUntil) {
        int inserted = jdbc.update("""
            INSERT INTO dispatch_holds (shipment_id, reason, forecast_snapshot, max_hold_until, shadow)
            SELECT ?, ?, ?::jsonb, ?, FALSE
             WHERE NOT EXISTS (
                SELECT 1 FROM dispatch_holds WHERE shipment_id = ? AND released_at IS NULL AND shadow = FALSE)
            """, shipmentId, reason.name(), forecastSnapshot, java.sql.Timestamp.valueOf(maxHoldUntil.atStartOfDay()), shipmentId);
        return inserted == 1;
    }

    public Optional<UUID> findShadowFor(UUID shipmentId) {
        List<UUID> ids = jdbc.queryForList(
            "SELECT id FROM dispatch_holds WHERE shipment_id = ? AND shadow = TRUE ORDER BY held_at DESC LIMIT 1",
            UUID.class, shipmentId);
        return ids.isEmpty() ? Optional.empty() : Optional.of(ids.get(0));
    }

    /** The one active (real, unreleased) hold for a shipment, if any - what HoldReleaseJob actually reviews. */
    public Optional<UUID> findActiveFor(UUID shipmentId) {
        List<UUID> ids = jdbc.queryForList(
            "SELECT id FROM dispatch_holds WHERE shipment_id = ? AND released_at IS NULL AND shadow = FALSE", UUID.class, shipmentId);
        return ids.isEmpty() ? Optional.empty() : Optional.of(ids.get(0));
    }

    public List<UUID> findActive() {
        return jdbc.queryForList(
            "SELECT id FROM dispatch_holds WHERE released_at IS NULL AND shadow = FALSE", UUID.class);
    }

    public Map<String, Object> load(UUID holdId) {
        return jdbc.queryForMap(
            "SELECT id, shipment_id, reason, max_hold_until FROM dispatch_holds WHERE id = ?", holdId);
    }

    /** Guarded, atomic - same shape used throughout this project (reservations, outbox) - so two overlapping review runs can't both release the same hold. */
    public int releaseIfNotAlready(UUID holdId, String releasedBy) {
        return jdbc.update(
            "UPDATE dispatch_holds SET released_at = now(), released_by = ? WHERE id = ? AND released_at IS NULL",
            releasedBy, holdId);
    }

    /** Week 8's FaultAttributor: "the platform overrode its own weather gate" - if a buyer's override shipped it into unsafe conditions, the platform owns that outcome, not the seller. */
    public boolean hadManualOverride(UUID shipmentId) {
        Long count = jdbc.queryForObject(
            "SELECT count(*) FROM dispatch_holds WHERE shipment_id = ? AND released_by = 'buyer_override'",
            Long.class, shipmentId);
        return count != null && count > 0;
    }
}
