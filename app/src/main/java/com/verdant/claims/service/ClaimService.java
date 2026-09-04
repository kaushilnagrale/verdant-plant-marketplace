package com.verdant.claims.service;

import com.verdant.claims.domain.ClaimType;
import com.verdant.claims.domain.ItemFacts;
import com.verdant.claims.domain.ShipmentFacts;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** UNIQUE (order_item_id) on claims (V28) is the actual defense against a double claim - this is just where the window/evidence checks live before that constraint would ever be reached. */
@Service
public class ClaimService {

    private final JdbcTemplate jdbc;
    private final ClaimRepository claims;
    private final FaultAttributor attributor;

    public ClaimService(JdbcTemplate jdbc, ClaimRepository claims, FaultAttributor attributor) {
        this.jdbc = jdbc;
        this.claims = claims;
        this.attributor = attributor;
    }

    @Transactional
    public UUID submit(UUID orderItemId, ClaimType type, String buyerSubjectId, List<String> photoS3Keys) {
        if (photoS3Keys.isEmpty()) {
            throw new InsufficientEvidenceException(orderItemId);
        }

        Map<String, Object> window = jdbc.queryForMap(
            "SELECT opens_at, closes_at FROM claim_windows WHERE order_item_id = ?", orderItemId);
        Instant opensAt = ((Timestamp) window.get("opens_at")).toInstant();
        Instant closesAt = ((Timestamp) window.get("closes_at")).toInstant();
        Instant now = Instant.now();
        if (now.isBefore(opensAt) || now.isAfter(closesAt)) {
            throw new ClaimWindowClosedException(orderItemId);
        }

        UUID shipmentId = shipmentIdFor(orderItemId);
        UUID claimId = claims.insert(orderItemId, shipmentId, buyerSubjectId, type);

        var attribution = attributor.attribute(shipmentFactsFor(shipmentId), itemFactsFor(orderItemId));
        claims.recordAttribution(claimId, attribution);

        photoS3Keys.forEach(key -> claims.insertPhoto(claimId, key));

        return claimId;
    }

    private UUID shipmentIdFor(UUID orderItemId) {
        return jdbc.queryForObject("""
            SELECT sh.id FROM order_items oi
              JOIN shipments sh ON sh.order_id = oi.order_id AND sh.seller_id = oi.seller_id
             WHERE oi.id = ?
            """, UUID.class, orderItemId);
    }

    private ShipmentFacts shipmentFactsFor(UUID shipmentId) {
        Map<String, Object> row = jdbc.queryForMap("""
            SELECT id, planned_dispatch_date, dispatched_at, delivered_at, transit_days FROM shipments WHERE id = ?
            """, shipmentId);
        return new ShipmentFacts(
            shipmentId,
            ((java.sql.Date) row.get("planned_dispatch_date")).toLocalDate(),
            ((Timestamp) row.get("dispatched_at")).toInstant(),
            ((Timestamp) row.get("delivered_at")).toInstant(),
            row.get("transit_days") == null ? 4 : ((Number) row.get("transit_days")).intValue());
    }

    private ItemFacts itemFactsFor(UUID orderItemId) {
        Integer handlingHours = jdbc.queryForObject("""
            SELECT l.handling_time_hours FROM order_items oi JOIN listings l ON l.id = oi.listing_id WHERE oi.id = ?
            """, Integer.class, orderItemId);
        return new ItemFacts(orderItemId, handlingHours);
    }
}
