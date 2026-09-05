package com.verdant.common.notification;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Records what WOULD be sent - no real SMS/email provider is integrated
 * anywhere in this project (see LAUNCH_CHECKLIST.md). status stays
 * 'RECORDED', never 'SENT', until a real provider exists to actually
 * deliver something - claiming SENT here would misrepresent a message
 * that never left this database. Real enough for what matters right now:
 * the DECISION to notify (and why) is genuine and tested, same reasoning
 * as Alerting.
 */
@Service
public class NotificationService {

    private final JdbcTemplate jdbc;

    public NotificationService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void notifyBuyer(UUID shipmentId, String eventType) {
        record(shipmentId, eventType, buyerEmailFor(shipmentId), bodyFor(eventType));
    }

    public void notifyOps(UUID shipmentId, String eventType) {
        record(shipmentId, eventType, "ops@verdant.example", bodyFor(eventType));
    }

    /** Payout failures (Week 8) are about a payout cycle, not a single shipment/order - order_id/shipment_id stay null, same as the schema already allows. */
    public void notifySeller(UUID sellerId, String eventType, String detail) {
        jdbc.update("""
            INSERT INTO notifications (event_type, recipient, status, body)
            VALUES (?, ?, 'RECORDED', ?)
            """, eventType, "seller:" + sellerId, bodyFor(eventType) + " " + detail);
    }

    private void record(UUID shipmentId, String eventType, String recipient, String body) {
        UUID orderId = jdbc.queryForObject("SELECT order_id FROM shipments WHERE id = ?", UUID.class, shipmentId);
        jdbc.update("""
            INSERT INTO notifications (order_id, shipment_id, event_type, recipient, status, body)
            VALUES (?, ?, ?, ?, 'RECORDED', ?)
            """, orderId, shipmentId, eventType, recipient, body);
    }

    private String buyerEmailFor(UUID shipmentId) {
        return jdbc.queryForObject("""
            SELECT o.buyer_email FROM shipments s JOIN orders o ON o.id = s.order_id WHERE s.id = ?
            """, String.class, shipmentId);
    }

    /**
     * Offering the override matters (Day 41.2) - some buyers genuinely
     * want it now, and removing the choice creates support load. No real
     * reply-handling channel exists to receive that override request yet
     * (no buyer auth or messaging pipeline - see Day 33's known
     * simplification), so this template documents the intent; acting on a
     * reply is a real future feature, not built here.
     */
    private String bodyFor(String eventType) {
        return switch (eventType) {
            case "HOLD_PLACED" -> "Hi, your plant is packed and ready, but the forecast where you are "
                + "makes it unsafe to ship right now. We're holding your parcel until conditions improve. "
                + "Nothing you need to do. Reply here if you'd rather we ship it now.";
            case "HOLD_RELEASED" -> "Good news - the forecast has cleared and your plant is on its way.";
            case "HOLD_ESCALATED" -> "A shipment hold has exceeded its maximum duration and needs manual review.";
            case "HOLD_OVERRIDE_ACKNOWLEDGED" -> "Got it - shipping your plant now despite the forecast, as you asked.";
            case "PAYOUT_FAILED" -> "Your payout could not be completed.";
            case "QUALITY_TIER_CHANGED" -> "Your seller quality tier has changed.";
            default -> eventType;
        };
    }
}
