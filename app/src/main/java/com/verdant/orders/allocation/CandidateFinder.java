package com.verdant.orders.allocation;

import com.verdant.cart.CartLine;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Not defined in the runbook. Finds every APPROVED seller's listing mapped
 * to the SAME canonical product as the buyer's chosen line - the buyer
 * clicked one seller's listing, but the platform can fulfill from whichever
 * candidate scores best (reuses the Week 3 canonical-product mapping
 * infrastructure rather than requiring a browse/cart UX change).
 */
@Service
public class CandidateFinder {

    private final JdbcTemplate jdbc;

    public CandidateFinder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Candidate> find(CartLine line, String destinationPincode) {
        List<UUID> canonicalIds = jdbc.queryForList(
            "SELECT canonical_product_id FROM listings WHERE id = ?", UUID.class, line.listingId());
        if (canonicalIds.isEmpty() || canonicalIds.get(0) == null) {
            return List.of(); // unmapped listing - nothing to allocate against
        }
        UUID canonicalProductId = canonicalIds.get(0);

        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT l.id AS listing_id, l.seller_id, l.price_amount, s.quality_score,
                   cp.display_name AS product_name, cp.max_transit_days,
                   COALESCE(inv.quantity - inv.buffer, 0) AS available_qty,
                   te.transit_days
              FROM listings l
              JOIN sellers s ON s.id = l.seller_id AND s.status = 'APPROVED'
              JOIN canonical_products cp ON cp.id = l.canonical_product_id
              LEFT JOIN inventory inv ON inv.listing_id = l.id
              JOIN pincode_zones origin_pz ON origin_pz.pincode = s.origin_pincode
              JOIN pincode_zones dest_pz ON dest_pz.pincode = ?
              JOIN transit_estimates te ON te.origin_zone = origin_pz.zone
                                        AND te.destination_zone = dest_pz.zone
             WHERE l.canonical_product_id = ? AND l.active = true
             ORDER BY l.id
            """, destinationPincode, canonicalProductId);

        if (rows.isEmpty()) {
            return List.of();
        }

        double minPrice = rows.stream().mapToDouble(r -> price(r)).min().orElse(0);
        double maxPrice = rows.stream().mapToDouble(r -> price(r)).max().orElse(0);
        double range = Math.max(0.01, maxPrice - minPrice); // avoid divide-by-zero when every candidate costs the same

        return rows.stream()
            .map(r -> new Candidate(
                (UUID) r.get("seller_id"),
                (UUID) r.get("listing_id"),
                (String) r.get("product_name"),
                ((Number) r.get("available_qty")).intValue(),
                ((Number) r.get("transit_days")).intValue(),
                ((Number) r.get("max_transit_days")).intValue(),
                ((BigDecimal) r.get("quality_score")).doubleValue(),
                (price(r) - minPrice) / range,
                true)) // already filtered to APPROVED sellers above; kept explicit for the engine's own defense-in-depth check
            .toList();
    }

    /** Not defined in the runbook. Looks up a listing's display name regardless of candidate availability, for unfulfillable-line reporting. */
    public String productNameFor(UUID listingId) {
        List<String> names = jdbc.queryForList("""
            SELECT COALESCE(cp.display_name, l.raw_title)
              FROM listings l
              LEFT JOIN canonical_products cp ON cp.id = l.canonical_product_id
             WHERE l.id = ?
            """, String.class, listingId);
        return names.isEmpty() ? "Unknown product" : names.get(0);
    }

    private double price(Map<String, Object> row) {
        return ((BigDecimal) row.get("price_amount")).doubleValue();
    }
}
