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
        return doRelease(reservationId, true);
    }

    /**
     * Saga compensation only (Day 33): releases a reservation even if it was
     * already fulfilled. Normally a fulfilled reservation is permanently
     * protected (see release()/releaseExpired()) - a completed sale should
     * never be silently released back onto the shelf. But if a payment that
     * succeeded is being refunded because something later in the pipeline
     * (settlement, shipment) failed irrecoverably, the stock MUST go back on
     * sale despite the sale normally being final. Only an active refund
     * decision justifies this - never call it from anywhere else.
     */
    public boolean releaseForRefund(UUID reservationId) {
        return doRelease(reservationId, false);
    }

    private boolean doRelease(UUID reservationId, boolean respectFulfilled) {
        List<Map<String, Object>> released = respectFulfilled
            ? jdbc.queryForList("""
                UPDATE reservations SET released_at = now()
                 WHERE id = ? AND released_at IS NULL AND fulfilled_at IS NULL
                RETURNING listing_id, quantity
                """, reservationId)
            : jdbc.queryForList("""
                UPDATE reservations SET released_at = now()
                 WHERE id = ? AND released_at IS NULL
                RETURNING listing_id, quantity
                """, reservationId);

        if (released.isEmpty()) {
            return false;
        }

        UUID listingId = (UUID) released.get(0).get("listing_id");
        int quantity = (int) released.get(0).get("quantity");
        creditBack(listingId, quantity);
        return true;
    }

    private void creditBack(UUID listingId, int quantity) {
        jdbc.update("""
            UPDATE inventory SET quantity = quantity + ?, updated_at = now()
             WHERE listing_id = ?
            """, quantity, listingId);
        jdbc.update("""
            INSERT INTO inventory_adjustments (listing_id, delta, reason, actor_id)
            VALUES (?, ?, 'RESERVATION_RELEASE', 'system')
            """, listingId, quantity);
    }

    /**
     * Sweeps every expired, unreleased reservation and releases it - but
     * never one that was already fulfilled by a real sale. Without the
     * fulfilled_at guard, a reservation whose payment succeeded would still
     * get its stock released back onto the shelf once its 15-minute window
     * passed, reselling a plant that was already bought.
     */
    public int releaseExpired() {
        List<UUID> expiredIds = jdbc.queryForList("""
            SELECT id FROM reservations
             WHERE expires_at < now() AND released_at IS NULL AND fulfilled_at IS NULL
            """, UUID.class);

        int released = 0;
        for (UUID id : expiredIds) {
            if (release(id)) {
                released++;
            }
        }
        return released;
    }

    /**
     * Compensating action for checkout: releases every reservation under one
     * order (e.g. because payment-order creation failed after stock was
     * already reserved). Not the expiry sweep - this runs immediately, not
     * because a window passed.
     */
    public int releaseByOrder(UUID orderId) {
        return releaseByOrder(orderId, true);
    }

    /** Saga compensation only - see releaseForRefund(). */
    public int releaseByOrderForRefund(UUID orderId) {
        return releaseByOrder(orderId, false);
    }

    private int releaseByOrder(UUID orderId, boolean respectFulfilled) {
        List<UUID> reservationIds = respectFulfilled
            ? jdbc.queryForList("""
                SELECT id FROM reservations
                 WHERE order_id = ? AND released_at IS NULL AND fulfilled_at IS NULL
                """, UUID.class, orderId)
            : jdbc.queryForList("""
                SELECT id FROM reservations WHERE order_id = ? AND released_at IS NULL
                """, UUID.class, orderId);

        int released = 0;
        for (UUID id : reservationIds) {
            boolean ok = respectFulfilled ? release(id) : releaseForRefund(id);
            if (ok) {
                released++;
            }
        }
        return released;
    }
}
