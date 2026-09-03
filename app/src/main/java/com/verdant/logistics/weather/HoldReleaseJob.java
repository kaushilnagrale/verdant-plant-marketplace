package com.verdant.logistics.weather;

import com.verdant.common.alerting.Alerting;
import com.verdant.common.notification.NotificationService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

@Service
public class HoldReleaseJob {

    private final DispatchHoldRepository holds;
    private final DispatchGate gate;
    private final NotificationService notifications;
    private final Alerting alerting;
    private final JdbcTemplate jdbc;

    public HoldReleaseJob(DispatchHoldRepository holds, DispatchGate gate, NotificationService notifications,
                           Alerting alerting, JdbcTemplate jdbc) {
        this.holds = holds;
        this.gate = gate;
        this.notifications = notifications;
        this.alerting = alerting;
        this.jdbc = jdbc;
    }

    /** Twice daily, after the forecast refresh - reviewing more often than the forecast actually changes would be wasted work. */
    @Scheduled(cron = "0 30 5,17 * * *")
    public void reviewHolds() {
        for (UUID holdId : holds.findActive()) {
            reviewOne(holdId);
        }
    }

    public void reviewOne(UUID holdId) {
        Map<String, Object> hold = holds.load(holdId);
        UUID shipmentId = (UUID) hold.get("shipment_id");
        LocalDate maxHoldUntil = ((java.sql.Timestamp) hold.get("max_hold_until")).toLocalDateTime().toLocalDate();

        if (Instant.now().isAfter(maxHoldUntil.atStartOfDay(java.time.ZoneOffset.UTC).toInstant())) {
            // Escalate, don't auto-release - exceeding the maximum hold
            // duration means a human needs to look at this, not that the
            // weather is now assumed safe.
            alerting.raise("Hold on shipment " + shipmentId + " exceeded maximum duration");
            notifications.notifyOps(shipmentId, "HOLD_ESCALATED");
            return;
        }

        if (gate.evaluate(shipmentId).isProceed()) {
            int claimed = holds.releaseIfNotAlready(holdId, "system");
            if (claimed == 1) {
                jdbc.update("UPDATE shipments SET status = 'READY' WHERE id = ?", shipmentId);
                notifications.notifyBuyer(shipmentId, "HOLD_RELEASED");
            }
        }
    }
}
