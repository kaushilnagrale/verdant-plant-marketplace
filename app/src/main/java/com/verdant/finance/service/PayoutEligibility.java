package com.verdant.finance.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Cash on delivery is a genuinely different timeline: the courier collects
 * cash from the buyer and remits it to the platform days later, so paying
 * a seller on delivery would mean paying out money the platform does not
 * yet hold. No order this platform actually creates is ever COD (checkout
 * has always been 100% prepaid via Razorpay, Week 4) - this branch is real
 * code, correctly modeling a real distinction, even though nothing today
 * exercises it outside a direct test fixture. See V30's own comment.
 */
@Component
public class PayoutEligibility {

    private static final Duration CLAIM_WINDOW = Duration.ofHours(72);

    private final JdbcTemplate jdbc;

    public PayoutEligibility(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isEligible(UUID orderItemId) {
        Map<String, Object> row = jdbc.queryForMap("""
            SELECT sh.id AS shipment_id, sh.delivered_at, o.is_cash_on_delivery
              FROM order_items oi
              JOIN orders o ON o.id = oi.order_id
              JOIN shipments sh ON sh.order_id = oi.order_id AND sh.seller_id = oi.seller_id
             WHERE oi.id = ?
            """, orderItemId);

        Timestamp deliveredAt = (Timestamp) row.get("delivered_at");
        if (deliveredAt == null) {
            return false;
        }

        // claim window must have closed
        if (Instant.now().isBefore(deliveredAt.toInstant().plus(CLAIM_WINDOW))) {
            return false;
        }

        // any open claim blocks the item entirely
        if (hasOpenClaim(orderItemId)) {
            return false;
        }

        // COD settles on courier remittance, not on delivery - a different timeline
        boolean isCod = Boolean.TRUE.equals(row.get("is_cash_on_delivery"));
        if (isCod) {
            return isRemitted((UUID) row.get("shipment_id"));
        }

        return true;
    }

    private boolean hasOpenClaim(UUID orderItemId) {
        Long count = jdbc.queryForObject("""
            SELECT count(*) FROM claims WHERE order_item_id = ? AND status NOT IN ('RESOLVED', 'REJECTED')
            """, Long.class, orderItemId);
        return count != null && count > 0;
    }

    private boolean isRemitted(UUID shipmentId) {
        Long count = jdbc.queryForObject(
            "SELECT count(*) FROM cod_remittances WHERE shipment_id = ?", Long.class, shipmentId);
        return count != null && count > 0;
    }
}
