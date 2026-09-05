package com.verdant.search.projection;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * One SQL query builds every document in the batch - the N+1 the runbook
 * itself calls out as the classic mistake here, avoided from the start
 * rather than found and fixed later.
 *
 * INNER JOIN canonical_products is deliberate, not an oversight: a listing
 * with no canonical_product_id (mapping_status = UNMAPPED, Week 3) has no
 * catalog identity yet - no display_name, no botanical_name, nothing
 * buyer-searchable - and is correctly excluded from the index entirely
 * until mapping completes, rather than indexed with placeholder text.
 *
 * LEFT JOIN inventory, not INNER: a real, separate, pre-existing gap found
 * while building this - nothing in this project's main source has ever
 * actually inserted an inventory row (InventoryService only ever reserves
 * against one that already exists; every inventory row this whole session
 * has seen came from a test fixture or manual SQL). An INNER JOIN would
 * silently drop every listing lacking one; LEFT JOIN plus COALESCE-to-zero
 * correctly shows it as out of stock instead, which is factually accurate
 * regardless of how that gap eventually gets closed.
 */
@Repository
public class ProjectionQueryRepository {

    private final JdbcTemplate jdbc;

    public ProjectionQueryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<ListingDocument> buildDocuments(List<UUID> listingIds) {
        if (listingIds.isEmpty()) {
            return List.of();
        }
        // A dynamically-sized IN (?,?,...) rather than = ANY(?): plain
        // JdbcTemplate (no NamedParameterJdbcTemplate anywhere in this
        // project) has no clean way to bind a raw Java array as a
        // Postgres array parameter without dropping into
        // connection.createArrayOf() by hand - this needs only the same
        // plain ? placeholders every other query in this codebase uses.
        String placeholders = String.join(",", Collections.nCopies(listingIds.size(), "?"));
        String now = Instant.now().toString(); // see ListingDocument's own doc comment for why this is a String, not an Instant

        return jdbc.query("""
            SELECT
                l.id                    AS listing_id,
                l.canonical_product_id,
                l.seller_id,
                cp.display_name,
                cp.botanical_name,
                cp.common_names,
                l.description,
                l.price_amount,
                COALESCE(i.quantity, 0) - COALESCE(i.buffer, 0)  AS sellable_quantity,
                pz.zone                 AS origin_zone,
                s.origin_pincode,
                s.quality_score         AS seller_quality_score,
                cp.heat_tolerance,
                cp.max_transit_days,
                cp.size_tier,
                cp.light_requirement,
                cp.pet_safe,
                (l.active AND s.status = 'APPROVED')              AS active
            FROM listings l
            JOIN canonical_products cp ON cp.id = l.canonical_product_id
            JOIN sellers s ON s.id = l.seller_id
            LEFT JOIN inventory i ON i.listing_id = l.id
            LEFT JOIN pincode_zones pz ON pz.pincode = s.origin_pincode
            WHERE l.id IN (%s)
            """.formatted(placeholders),
            (rs, rowNum) -> {
                int sellableQuantity = Math.max(0, rs.getInt("sellable_quantity"));
                return new ListingDocument(
                    (UUID) rs.getObject("listing_id"),
                    (UUID) rs.getObject("canonical_product_id"),
                    (UUID) rs.getObject("seller_id"),
                    rs.getString("display_name"),
                    rs.getString("botanical_name"),
                    toList(rs.getArray("common_names")),
                    rs.getString("description"),
                    rs.getBigDecimal("price_amount"),
                    sellableQuantity > 0,
                    sellableQuantity,
                    rs.getString("origin_zone"),
                    rs.getString("origin_pincode"),
                    rs.getFloat("seller_quality_score"),
                    rs.getString("heat_tolerance"),
                    rs.getInt("max_transit_days"),
                    rs.getString("size_tier"),
                    rs.getString("light_requirement"),
                    (Boolean) rs.getObject("pet_safe"),
                    rs.getBoolean("active"),
                    now);
            },
            listingIds.toArray());
    }

    private List<String> toList(Array sqlArray) throws SQLException {
        if (sqlArray == null) {
            return List.of();
        }
        Object[] raw = (Object[]) sqlArray.getArray();
        List<String> result = new ArrayList<>(raw.length);
        for (Object o : raw) {
            result.add((String) o);
        }
        return result;
    }
}
