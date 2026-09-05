package com.verdant.inventory;

import com.verdant.common.outbox.OutboxWriter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class InventoryService {

    private final JdbcTemplate jdbc;
    private final OutboxWriter outbox;

    public InventoryService(JdbcTemplate jdbc, OutboxWriter outbox) {
        this.jdbc = jdbc;
        this.outbox = outbox;
    }

    /**
     * The check ("is there enough stock?") and the write (decrement it) are
     * one atomic statement, not two. Postgres evaluates the WHERE clause
     * against the live row under its own row lock at write time, so there is
     * no gap for a concurrent caller to read stale data. 0 rows updated means
     * "not enough stock" - a clean decline, not a race.
     *
     * REAL BUG, found by InventoryConcurrencyTest failing after Day 49's
     * outbox wiring added the MANDATORY-propagation outbox.write() call
     * below: this method used to rely entirely on being called from
     * reserveAll()'s own @Transactional for an enclosing transaction to
     * exist. That is fragile on its own terms (a landmine for any future
     * caller), and Day 49 turned the latent fragility into a real, live
     * bug - called standalone with no active transaction, the decrement
     * and both inserts above each auto-commit individually (plain
     * JdbcTemplate calls, no transaction), and only THEN does
     * outbox.write() throw IllegalTransactionStateException - meaning
     * stock was already, permanently decremented before the exception
     * was thrown, for every caller that hit this path outside a
     * transaction. @Transactional here makes reserve() correct standalone
     * (a real transaction wraps decrement+inserts+outbox write as one
     * unit again); it does nothing extra when called from reserveAll()'s
     * own self-invocation (Spring's AOP proxy is skipped on a same-class
     * call, so this annotation is simply inert there - reserveAll()'s own
     * outer transaction is what actually applies, same as before).
     */
    @Transactional
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
        // MANDATORY propagation (OutboxWriter, Week 6) - safe now whether
        // reserve() is called standalone (its own @Transactional above
        // provides the transaction) or from reserveAll() (that method's
        // own @Transactional does instead, via the ambient-transaction
        // self-invocation behavior described on reserve()'s own comment).
        outbox.write("LISTING", listingId, "INVENTORY_CHANGED", Map.of("listingId", listingId.toString()));
        return true;
    }

    /**
     * Reserves every line under one order as a single database transaction:
     * if any line can't be reserved, everything reserved so far in this call
     * rolls back automatically, so a cart never ends up half-reserved.
     * Called externally (by CheckoutService, a different bean) so the
     * @Transactional proxy actually applies - an internal self-call from
     * another method on this same class would silently skip it, a common
     * Spring AOP footgun (harmless here now that reserve() itself is also
     * @Transactional and correct on its own - see its own comment).
     */
    @Transactional
    public void reserveAll(UUID orderId, List<ReservationLine> lines) {
        for (ReservationLine line : lines) {
            if (!reserve(line.listingId(), orderId, line.quantity())) {
                throw new InsufficientStockException(line.listingId());
            }
        }
    }

    /**
     * Real-time read, used by CartPricingService (Week 9) to revalidate
     * against Postgres - never the search index, which may be stale -
     * before a buyer's cart is priced or checked out. A scalar subquery
     * rather than queryForObject() directly on inventory: a listing with
     * no inventory row at all (a real, separate, pre-existing gap - see
     * ProjectionQueryRepository's own doc comment) must read as 0
     * available, not throw EmptyResultDataAccessException.
     */
    public int sellableQuantity(UUID listingId) {
        Integer available = jdbc.queryForObject("""
            SELECT COALESCE((SELECT quantity - buffer FROM inventory WHERE listing_id = ?), 0)
            """, Integer.class, listingId);
        return available == null ? 0 : available;
    }
}
