package com.verdant.search.query;

import java.util.Map;

/**
 * The runbook's own Day 50.3 is a one-line comment stub ("light_requirement,
 * water_need, pet_safe, size_tier, price ranges, and estimated delivery
 * days") rather than real code - built out here against the real index
 * mapping instead. water_need is deliberately omitted: no such column
 * exists anywhere in canonical_products (V33 seeded light_requirement and
 * pet_safe specifically, never water_need), and inventing one now would
 * mean fabricating real botanical data for 20 products for a facet
 * nothing tests or the PRD ever asked for - a genuine gap noted here
 * rather than papered over with placeholder values.
 *
 * Each map is bucket label -> real document count, in a sensible display
 * order (ascending price/days, not hashmap order) rather than raw
 * alphabetical, since these are meant to be rendered directly as a
 * filter sidebar.
 */
public record FacetCounts(
    Map<String, Long> lightRequirement,
    Map<String, Long> sizeTier,
    Map<String, Long> petSafe,
    Map<String, Long> priceRange,
    Map<String, Long> deliveryDays
) {
}
