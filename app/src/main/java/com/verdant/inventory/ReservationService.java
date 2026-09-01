package com.verdant.inventory;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ReservationService {

    private final JdbcTemplate jdbc;

    public ReservationService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Releases one reservation and credits its stock back, but only if this
     * call is the one that actually flips released_at from NULL. The
     * WHERE...RETURNING is a single atomic statement: whichever caller wins
     * the row gets its listing_id/quantity back and credits inventory; a
     * second concurrent (or later, repeated) caller sees an empty result and
     * does nothing. Same idea as InventoryService.reserve() - the guard and
     * the write are one indivisible step, not read-then-write.
     */
    public boolean release(UUID reservationId) {
        List<Map<String, Object>> released = jdbc.queryForList("""
            UPDATE reservations
               SET released_at = now()
             WHERE id = ?
               AND released_at IS NULL
            RETURNING listing_id, quantity
            """, reservationId);

        if (released.isEmpty()) {
            return false;
        }

        UUID listingId = (UUID) released.get(0).get("listing_id");
        int quantity = (int) released.get(0).get("quantity");

        jdbc.update("""
            UPDATE inventory SET quantity = quantity + ?, updated_at = now()
             WHERE listing_id = ?
            """, quantity, listingId);
        jdbc.update("""
            INSERT INTO inventory_adjustments (listing_id, delta, reason, actor_id)
            VALUES (?, ?, 'RESERVATION_RELEASE', 'system')
            """, listingId, quantity);
        return true;
    }

    /** Sweeps every expired, unreleased reservation and releases it. */
    public int releaseExpired() {
        List<UUID> expiredIds = jdbc.queryForList("""
            SELECT id FROM reservations WHERE expires_at < now() AND released_at IS NULL
            """, UUID.class);

        int released = 0;
        for (UUID id : expiredIds) {
            if (release(id)) {
                released++;
            }
        }
        return released;
    }
}
