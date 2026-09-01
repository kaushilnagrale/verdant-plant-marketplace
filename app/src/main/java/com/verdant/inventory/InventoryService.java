package com.verdant.inventory;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class InventoryService {

    private final JdbcTemplate jdbc;

    public InventoryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The check ("is there enough stock?") and the write (decrement it) are
     * one atomic statement, not two. Postgres evaluates the WHERE clause
     * against the live row under its own row lock at write time, so there is
     * no gap for a concurrent caller to read stale data. 0 rows updated means
     * "not enough stock" - a clean decline, not a race.
     */
    public boolean reserve(UUID listingId, UUID orderId, int quantity) {
        int rowsUpdated = jdbc.update("""
            UPDATE inventory
               SET quantity = quantity - ?, updated_at = now()
             WHERE listing_id = ?
               AND quantity - buffer >= ?
            """, quantity, listingId, quantity);

        if (rowsUpdated == 0) {
            return false;
        }

        jdbc.update("""
            INSERT INTO reservations (order_id, listing_id, quantity, expires_at)
            VALUES (?, ?, ?, now() + interval '15 minutes')
            """, orderId, listingId, quantity);
        jdbc.update("""
            INSERT INTO inventory_adjustments (listing_id, delta, reason, actor_id)
            VALUES (?, ?, 'SALE', 'system')
            """, listingId, -quantity);
        return true;
    }
}
