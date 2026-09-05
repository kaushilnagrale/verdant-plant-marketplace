package com.verdant.assistant.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/** Species comes from the order, never from the model guessing at the photo (60.2's own emphasis) - the platform already knows exactly what was sold, so re-deriving it from an image would introduce error for no reason. */
@Repository
public class OrderItemSpeciesResolver {

    private final JdbcTemplate jdbc;

    public OrderItemSpeciesResolver(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String speciesOf(UUID orderItemId) {
        List<String> names = jdbc.queryForList("""
            SELECT cp.botanical_name
              FROM order_items oi
              JOIN listings l ON l.id = oi.listing_id
              JOIN canonical_products cp ON cp.id = l.canonical_product_id
             WHERE oi.id = ?
            """, String.class, orderItemId);
        return names.isEmpty() ? "UNKNOWN" : names.get(0);
    }
}
