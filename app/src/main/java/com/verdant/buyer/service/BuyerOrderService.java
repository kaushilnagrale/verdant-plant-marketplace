package com.verdant.buyer.service;

import com.verdant.buyer.domain.BuyerOrderDetail;
import com.verdant.buyer.domain.BuyerOrderItem;
import com.verdant.buyer.domain.BuyerOrderSummary;
import com.verdant.buyer.domain.ShipmentTracking;
import com.verdant.buyer.domain.ShipmentTrackingEvent;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Same real join shape as SellerOrderService.myOrders() (that one scopes
 * to a seller's own lines within an order; this scopes to a buyer's own
 * orders, and shows every seller's lines within them, since the buyer -
 * unlike any one seller - is meant to see the whole order they placed).
 */
@Service
public class BuyerOrderService {

    private final JdbcTemplate jdbc;

    public BuyerOrderService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<BuyerOrderSummary> myOrders(UUID buyerId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT o.id AS order_id, o.status AS order_status, o.total_amount, o.created_at,
                   oi.listing_id, COALESCE(cp.display_name, l.raw_title) AS product_name,
                   s.business_name AS seller_business_name, oi.quantity, oi.line_total
              FROM orders o
              JOIN order_items oi ON oi.order_id = o.id
              JOIN listings l ON l.id = oi.listing_id
              JOIN sellers s ON s.id = oi.seller_id
              LEFT JOIN canonical_products cp ON cp.id = l.canonical_product_id
             WHERE o.buyer_id = ?
             ORDER BY o.created_at DESC
            """, buyerId);

        Map<UUID, List<Map<String, Object>>> byOrder = rows.stream()
            .collect(Collectors.groupingBy(r -> (UUID) r.get("order_id"), LinkedHashMap::new, Collectors.toList()));

        return byOrder.entrySet().stream()
            .map(e -> {
                Map<String, Object> first = e.getValue().get(0);
                return new BuyerOrderSummary(e.getKey(), (String) first.get("order_status"),
                    (BigDecimal) first.get("total_amount"), ((Timestamp) first.get("created_at")).toInstant(),
                    toItems(e.getValue()));
            })
            .toList();
    }

    /** 404, not 403, for an order that exists but belongs to someone else - same real RLS-adjacent convention this project uses everywhere else (never confirm another buyer's order even exists). */
    public BuyerOrderDetail orderDetail(UUID buyerId, UUID orderId) {
        Map<String, Object> order = jdbc.queryForList("""
            SELECT id, status, total_amount, created_at, recipient_name, recipient_phone,
                   address_line1, address_line2, city, state, destination_pincode
              FROM orders WHERE id = ? AND buyer_id = ?
            """, orderId, buyerId)
            .stream().findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such order"));

        List<Map<String, Object>> itemRows = jdbc.queryForList("""
            SELECT oi.listing_id, COALESCE(cp.display_name, l.raw_title) AS product_name,
                   s.business_name AS seller_business_name, oi.quantity, oi.line_total
              FROM order_items oi
              JOIN listings l ON l.id = oi.listing_id
              JOIN sellers s ON s.id = oi.seller_id
              LEFT JOIN canonical_products cp ON cp.id = l.canonical_product_id
             WHERE oi.order_id = ?
            """, orderId);

        List<Map<String, Object>> shipmentRows = jdbc.queryForList("""
            SELECT sh.id, s.business_name AS seller_business_name, sh.status, sh.courier, sh.awb_number,
                   sh.transit_days, sh.dispatched_at, sh.delivered_at
              FROM shipments sh
              JOIN sellers s ON s.id = sh.seller_id
             WHERE sh.order_id = ?
             ORDER BY sh.created_at
            """, orderId);

        List<ShipmentTracking> shipments = shipmentRows.stream().map(row -> {
            UUID shipmentId = (UUID) row.get("id");
            List<ShipmentTrackingEvent> events = jdbc.query("""
                SELECT status, location, remark, occurred_at FROM shipment_events
                 WHERE shipment_id = ? ORDER BY occurred_at
                """, (rs, n) -> new ShipmentTrackingEvent(rs.getString("status"), rs.getString("location"),
                    rs.getString("remark"), rs.getTimestamp("occurred_at").toInstant()), shipmentId);

            return new ShipmentTracking(shipmentId, (String) row.get("seller_business_name"), (String) row.get("status"),
                (String) row.get("courier"), (String) row.get("awb_number"), (Integer) row.get("transit_days"),
                toInstant(row.get("dispatched_at")), toInstant(row.get("delivered_at")), events);
        }).toList();

        return new BuyerOrderDetail((UUID) order.get("id"), (String) order.get("status"),
            (BigDecimal) order.get("total_amount"), ((Timestamp) order.get("created_at")).toInstant(),
            (String) order.get("recipient_name"), (String) order.get("recipient_phone"),
            (String) order.get("address_line1"), (String) order.get("address_line2"),
            (String) order.get("city"), (String) order.get("state"), (String) order.get("destination_pincode"),
            toItems(itemRows), shipments);
    }

    private List<BuyerOrderItem> toItems(List<Map<String, Object>> rows) {
        return rows.stream()
            .map(r -> new BuyerOrderItem((UUID) r.get("listing_id"), (String) r.get("product_name"),
                (String) r.get("seller_business_name"), (int) r.get("quantity"), (BigDecimal) r.get("line_total")))
            .toList();
    }

    private Instant toInstant(Object timestamp) {
        return timestamp == null ? null : ((Timestamp) timestamp).toInstant();
    }
}
