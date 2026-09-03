package com.verdant.catalog;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/**
 * Not in the runbook, and a real gap: every listings endpoint until now
 * (ListingController) is seller-facing - authenticated, scoped to the
 * caller's own listings. A buyer browsing the catalog needs a genuinely
 * public read path. permitAll, same reasoning as /v1/cart/** - read-only,
 * no buyer identity system exists to authenticate against anyway.
 */
@RestController
@RequestMapping("/v1/catalog")
public class CatalogController {

    private final JdbcTemplate jdbc;

    public CatalogController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    public List<CatalogListingSummary> browse() {
        return jdbc.query("""
            SELECT l.id, l.raw_title, l.price_amount, l.seller_id, s.business_name AS seller_name,
                   cp.display_name AS product_name
              FROM listings l
              JOIN sellers s ON s.id = l.seller_id AND s.status = 'APPROVED'
              LEFT JOIN canonical_products cp ON cp.id = l.canonical_product_id
             WHERE l.active = true
             ORDER BY l.created_at DESC
            """, (rs, rowNum) -> new CatalogListingSummary(
                (UUID) rs.getObject("id"),
                rs.getString("raw_title"),
                rs.getString("product_name"),
                rs.getBigDecimal("price_amount"),
                (UUID) rs.getObject("seller_id"),
                rs.getString("seller_name")));
    }

    @GetMapping("/{id}")
    public CatalogListingDetail detail(@PathVariable UUID id) {
        List<CatalogListingDetail> rows = jdbc.query("""
            SELECT l.id, l.raw_title, l.description, l.price_amount, l.handling_time_hours,
                   l.seller_id, s.business_name AS seller_name, cp.display_name AS product_name
              FROM listings l
              JOIN sellers s ON s.id = l.seller_id AND s.status = 'APPROVED'
              LEFT JOIN canonical_products cp ON cp.id = l.canonical_product_id
             WHERE l.id = ? AND l.active = true
            """, (rs, rowNum) -> new CatalogListingDetail(
                (UUID) rs.getObject("id"),
                rs.getString("raw_title"),
                rs.getString("product_name"),
                rs.getString("description"),
                rs.getBigDecimal("price_amount"),
                (UUID) rs.getObject("seller_id"),
                rs.getString("seller_name"),
                rs.getInt("handling_time_hours")),
            id);

        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Listing not found: " + id);
        }
        return rows.get(0);
    }
}
