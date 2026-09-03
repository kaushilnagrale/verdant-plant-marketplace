package com.verdant.logistics.weather;

import com.verdant.common.notification.NotificationService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Offering the override matters (Day 41.2 doc note): some buyers genuinely
 * want their plant shipped now despite the risk, and removing the choice
 * creates support load. No real inbound channel exists yet to receive a
 * buyer's reply and call this automatically (no buyer auth or messaging
 * pipeline - Day 33's known simplification) - this is the service method
 * that action would call, exercised directly here rather than through an
 * endpoint that has nothing real to receive from yet.
 */
@Service
public class HoldOverrideService {

    private final DispatchHoldRepository holds;
    private final NotificationService notifications;
    private final JdbcTemplate jdbc;

    public HoldOverrideService(DispatchHoldRepository holds, NotificationService notifications, JdbcTemplate jdbc) {
        this.holds = holds;
        this.notifications = notifications;
        this.jdbc = jdbc;
    }

    /** @return true if this call is the one that actually released the hold (idempotent - a repeated override request is a no-op, not an error). */
    public boolean overrideAndShipNow(UUID holdId, UUID shipmentId) {
        int claimed = holds.releaseIfNotAlready(holdId, "buyer_override");
        if (claimed == 1) {
            jdbc.update("UPDATE shipments SET status = 'READY' WHERE id = ?", shipmentId);
            notifications.notifyBuyer(shipmentId, "HOLD_OVERRIDE_ACKNOWLEDGED");
        }
        return claimed == 1;
    }
}
