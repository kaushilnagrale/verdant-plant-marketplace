package com.verdant.checkout;

import com.verdant.inventory.ReservationService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * A buyer who never completes payment leaves an order stuck in CREATED with
 * stock reserved against it forever unless something expires it.
 *
 * Ordering matters here: the order is flipped to EXPIRED FIRST, with the
 * same atomic guard (WHERE status = 'CREATED') PaymentVerificationService
 * uses to flip it to PAID. Postgres's row lock means only one of those two
 * concurrent UPDATEs can ever win for a given order - so if a buyer's
 * payment completes at the exact instant their window expires, exactly one
 * side wins cleanly. Reservations are only released when this sweep is the
 * one that won; if verification won instead, this sweep's guarded update
 * affects 0 rows and it never touches that order's reservation at all.
 */
@Service
public class OrderExpiryService {

    private final ReservationService reservations;
    private final JdbcTemplate jdbc;

    public OrderExpiryService(ReservationService reservations, JdbcTemplate jdbc) {
        this.reservations = reservations;
        this.jdbc = jdbc;
    }

    public int expireOrders() {
        List<UUID> candidates = jdbc.queryForList("""
            SELECT id FROM orders WHERE status = 'CREATED' AND expires_at < now()
            """, UUID.class);

        int expired = 0;
        for (UUID orderId : candidates) {
            int rowsUpdated = jdbc.update("""
                UPDATE orders SET status = 'EXPIRED'
                 WHERE id = ? AND status = 'CREATED' AND expires_at < now()
                """, orderId);

            if (rowsUpdated > 0) {
                reservations.releaseByOrder(orderId);
                expired++;
            }
        }
        return expired;
    }
}
