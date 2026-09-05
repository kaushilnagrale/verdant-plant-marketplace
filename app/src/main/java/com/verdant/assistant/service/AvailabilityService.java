package com.verdant.assistant.service;

import com.verdant.assistant.domain.AvailableListing;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The deterministic half of the gift concierge (59.1's own framing) - a
 * real SQL query against real inventory/allocation data, run BEFORE the
 * model is ever called, so a recommendation can only ever contain a
 * listing that is actually in stock and can actually be delivered alive
 * to the buyer's own destination pincode. Deliberately a direct
 * Postgres query, not a search-index (OpenSearch) one: Week 9's own
 * thesis is that a read authorizing a purchase decision must not be
 * stale, and recommending a specific gift is exactly that kind of read,
 * not passive browsing.
 *
 * Reuses the SAME real serviceability logic ListingSearchService (Week
 * 9) already established - a per-candidate check against real
 * transit_estimates and the product's own max_transit_days, not a
 * static zone list - because a gift arriving dead is exactly the
 * failure this whole platform is built to prevent, concierge feature or
 * not.
 */
@Service
public class AvailabilityService {

    private final JdbcTemplate jdbc;

    public AvailabilityService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Every real canonical product display name currently mentioned by name in the given guidance text - a simple, real, direct way to turn "what the retrieved chunks talk about" into real catalog identities, rather than trusting the model to name them later. */
    public Set<String> productNamesMentionedIn(String guidanceText) {
        List<String> allNames = jdbc.queryForList("SELECT display_name FROM canonical_products", String.class);
        String lower = guidanceText.toLowerCase();
        return allNames.stream().filter(name -> lower.contains(name.toLowerCase())).collect(Collectors.toSet());
    }

    public List<AvailableListing> find(Set<String> productNames, String destinationPincode,
                                        BigDecimal budget, LocalDate requestedDeliveryDate) {
        if (productNames.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(productNames.size(), "?"));

        List<Object> params = new java.util.ArrayList<>(productNames);
        params.add(destinationPincode);
        params.add(budget == null ? new BigDecimal("999999") : budget);

        return jdbc.query("""
            SELECT l.id AS listing_id, l.seller_id, cp.display_name, l.price_amount, te.transit_days
              FROM listings l
              JOIN sellers s ON s.id = l.seller_id AND s.status = 'APPROVED' AND s.on_vacation = false
              JOIN canonical_products cp ON cp.id = l.canonical_product_id AND cp.display_name IN (%s)
              LEFT JOIN inventory inv ON inv.listing_id = l.id
              JOIN pincode_zones origin_pz ON origin_pz.pincode = s.origin_pincode
              JOIN pincode_zones dest_pz ON dest_pz.pincode = ?
              JOIN transit_estimates te ON te.origin_zone = origin_pz.zone AND te.destination_zone = dest_pz.zone
             WHERE l.active = true
               AND COALESCE(inv.quantity - inv.buffer, 0) > 0
               AND l.price_amount <= ?
               AND te.transit_days <= cp.max_transit_days
             ORDER BY te.transit_days, l.price_amount
            """.formatted(placeholders),
            (rs, rowNum) -> new AvailableListing(
                (java.util.UUID) rs.getObject("listing_id"),
                (java.util.UUID) rs.getObject("seller_id"),
                rs.getString("display_name"),
                rs.getBigDecimal("price_amount"),
                rs.getInt("transit_days")),
            params.toArray());
    }
}
