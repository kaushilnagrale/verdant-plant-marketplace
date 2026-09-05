package com.verdant.catalogservice;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Deliberately plain JdbcTemplate over the real canonical_products/
 * listings tables, not JPA entities re-declared here - this service
 * reads the SAME real rows the monolith's own catalog code writes
 * (shared database, V39's own real trade-off), and re-declaring a
 * second, independent ORM mapping of that schema would be a real,
 * separate source of drift this proof-of-concept doesn't need to take
 * on to prove the actual point: a differently-privileged connection
 * can serve real catalog reads.
 */
@Repository
public class ProductRepository {

    private final JdbcTemplate jdbc;

    public ProductRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record ProductSummary(UUID id, String displayName, String botanicalName, int listingCount) { }

    public Optional<ProductSummary> findById(UUID id) {
        return jdbc.query("""
            SELECT p.id, p.display_name, p.botanical_name,
                   (SELECT count(*) FROM listings l WHERE l.canonical_product_id = p.id) AS listing_count
              FROM canonical_products p
             WHERE p.id = ?
            """,
            (rs, rowNum) -> new ProductSummary(
                (UUID) rs.getObject("id"), rs.getString("display_name"),
                rs.getString("botanical_name"), rs.getInt("listing_count")),
            id).stream().findFirst();
    }

    public List<ProductSummary> search(String query, int limit) {
        return jdbc.query("""
            SELECT p.id, p.display_name, p.botanical_name,
                   (SELECT count(*) FROM listings l WHERE l.canonical_product_id = p.id) AS listing_count
              FROM canonical_products p
             WHERE p.display_name ILIKE ?
             ORDER BY p.display_name
             LIMIT ?
            """,
            (rs, rowNum) -> new ProductSummary(
                (UUID) rs.getObject("id"), rs.getString("display_name"),
                rs.getString("botanical_name"), rs.getInt("listing_count")),
            "%" + query + "%", limit);
    }
}
