package com.verdant.orders.saga;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** Persists real shipment rows (see V22) - only the actual courier call is stubbed, not the record-keeping. */
@Component
public class StubLogisticsGateway implements LogisticsGateway {

    private final JdbcTemplate jdbc;
    private final AtomicInteger cancelCallCount = new AtomicInteger(0);

    public StubLogisticsGateway(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String book(UUID orderId, UUID sellerId) {
        String reference = "SHIP-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        // origin/destination pincode (Week 7) - populated here so
        // ShipmentBookingService has real values to book the real courier
        // against later, without a separate lookup of its own.
        jdbc.update("""
            INSERT INTO shipments (order_id, seller_id, status, reference, origin_pincode, destination_pincode)
            VALUES (?, ?, 'BOOKED', ?,
                    (SELECT origin_pincode FROM sellers WHERE id = ?),
                    (SELECT destination_pincode FROM orders WHERE id = ?))
            ON CONFLICT (order_id, seller_id)
            DO UPDATE SET status = 'BOOKED', reference = EXCLUDED.reference, cancelled_at = NULL
            """, orderId, sellerId, reference, sellerId, orderId);
        return reference;
    }

    @Override
    public void cancel(UUID orderId, UUID sellerId) {
        cancelCallCount.incrementAndGet();
        jdbc.update("""
            UPDATE shipments SET status = 'CANCELLED', cancelled_at = now()
             WHERE order_id = ? AND seller_id = ?
            """, orderId, sellerId);
    }

    public boolean isBooked(UUID orderId, UUID sellerId) {
        List<String> statuses = jdbc.queryForList(
            "SELECT status FROM shipments WHERE order_id = ? AND seller_id = ?", String.class, orderId, sellerId);
        return !statuses.isEmpty() && !"CANCELLED".equals(statuses.get(0));
    }

    public int cancelCallCount() {
        return cancelCallCount.get();
    }

    public void reset() {
        cancelCallCount.set(0);
    }
}
