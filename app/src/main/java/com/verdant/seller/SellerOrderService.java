package com.verdant.seller;

import com.verdant.quality.service.SellerQualityEventRepository;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class SellerOrderService {

    private final JdbcTemplate jdbc;
    private final SellerQualityEventRepository qualityEvents;

    public SellerOrderService(JdbcTemplate jdbc, SellerQualityEventRepository qualityEvents) {
        this.jdbc = jdbc;
        this.qualityEvents = qualityEvents;
    }

    /** Only this seller's own items within an order - a multi-seller order never leaks another seller's lines. */
    public List<SellerOrderSummary> myOrders(UUID sellerId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT o.id AS order_id, o.status AS order_status, o.buyer_email, o.created_at,
                   oi.listing_id, COALESCE(cp.display_name, l.raw_title) AS product_name,
                   oi.quantity, oi.line_total, sh.status AS shipment_status
              FROM order_items oi
              JOIN orders o ON o.id = oi.order_id
              JOIN listings l ON l.id = oi.listing_id
              LEFT JOIN canonical_products cp ON cp.id = l.canonical_product_id
              LEFT JOIN shipments sh ON sh.order_id = oi.order_id AND sh.seller_id = oi.seller_id
             WHERE oi.seller_id = ?
             ORDER BY o.created_at DESC
            """, sellerId);

        Map<UUID, List<Map<String, Object>>> byOrder = rows.stream()
            .collect(Collectors.groupingBy(r -> (UUID) r.get("order_id"), LinkedHashMap::new, Collectors.toList()));

        return byOrder.entrySet().stream()
            .map(e -> toSummary(e.getKey(), e.getValue()))
            .toList();
    }

    private SellerOrderSummary toSummary(UUID orderId, List<Map<String, Object>> rows) {
        Map<String, Object> first = rows.get(0);
        List<SellerOrderItem> items = rows.stream()
            .map(r -> new SellerOrderItem((UUID) r.get("listing_id"), (String) r.get("product_name"),
                (int) r.get("quantity"), (BigDecimal) r.get("line_total")))
            .toList();
        return new SellerOrderSummary(orderId, (String) first.get("order_status"), (String) first.get("buyer_email"),
            items, (String) first.get("shipment_status"), ((Timestamp) first.get("created_at")).toInstant());
    }

    /** Guarded, atomic - only a BOOKED shipment can become PACKED, same shape used throughout this project. */
    public void markPacked(UUID sellerId, UUID orderId) {
        int rows = jdbc.update("""
            UPDATE shipments SET status = 'PACKED', packed_at = now()
             WHERE order_id = ? AND seller_id = ? AND status = 'BOOKED'
            """, orderId, sellerId);
        if (rows == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Shipment is not in a packable state");
        }
    }

    public void markDispatched(UUID sellerId, UUID orderId) {
        int rows = jdbc.update("""
            UPDATE shipments SET status = 'DISPATCHED', dispatched_at = now()
             WHERE order_id = ? AND seller_id = ? AND status = 'PACKED'
            """, orderId, sellerId);
        if (rows == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Shipment is not in a dispatchable state");
        }
        recordDispatchQualityEvent(sellerId, orderId);
    }

    /**
     * Real quality signal (Week 10), deliberately a PLAIN comparison
     * against planned_dispatch_date - not FaultAttributor's own, more
     * lenient +24h threshold (Week 8). Those answer different real
     * questions: FaultAttributor decides who pays for a dead plant, and
     * gives the seller a generous benefit of the doubt before assigning
     * financial liability; this decides whether the seller met the
     * delivery-time promise they themselves made, which is the plain,
     * direct thing 53.2 asks for ("dispatched within your stated handling
     * time"), no extra grace. planned_dispatch_date is a DATE - dispatch
     * counts as on time any time during that calendar day (IST), i.e.
     * strictly before the day after it starts.
     */
    private void recordDispatchQualityEvent(UUID sellerId, UUID orderId) {
        Map<String, Object> row = jdbc.queryForMap("""
            SELECT planned_dispatch_date, dispatched_at FROM shipments WHERE order_id = ? AND seller_id = ?
            """, orderId, sellerId);
        LocalDate plannedDate = ((java.sql.Date) row.get("planned_dispatch_date")).toLocalDate();
        var dispatchedAt = ((Timestamp) row.get("dispatched_at")).toInstant();
        var deadline = plannedDate.plusDays(1).atStartOfDay(ZoneId.of("Asia/Kolkata")).toInstant();

        String eventType = dispatchedAt.isBefore(deadline) ? "DISPATCH_ON_TIME" : "DISPATCH_LATE";
        qualityEvents.record(sellerId, eventType, orderId);
    }
}
