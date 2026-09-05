package com.verdant.search.projection;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Mirrors infra/opensearch/listings-index.json field for field. Field
 * names are snake_case to match the index mapping exactly - the
 * OpenSearch Java client bundles its own Jackson-based JSON handling,
 * entirely separate from Spring's own ObjectMapper bean (which stays
 * camelCase for every REST response), so annotating here has no effect
 * on anything else in the app.
 *
 * REAL BUG, found by every real bulk index attempt failing: indexed_at
 * was originally a java.time.Instant, which the OpenSearch client's
 * bundled JacksonJsonpMapper cannot serialize without the separate
 * jackson-datatype-jsr310 module explicitly registered on its internal
 * ObjectMapper - not something this project's own dependencies provide,
 * and not worth chasing down a way to reach into opensearch-java's
 * internal mapper construction to add it. A plain ISO-8601 String needs
 * no module at all and OpenSearch's own "date" field type parses it
 * exactly the same way; UUID/BigDecimal/List<String> above are all
 * natively Jackson-serializable with zero extra configuration, which is
 * exactly why only this one field was ever a problem.
 */
public record ListingDocument(
    @JsonProperty("listing_id") UUID listingId,
    @JsonProperty("canonical_product_id") UUID canonicalProductId,
    @JsonProperty("seller_id") UUID sellerId,
    @JsonProperty("display_name") String displayName,
    @JsonProperty("botanical_name") String botanicalName,
    @JsonProperty("common_names") List<String> commonNames,
    @JsonProperty("description") String description,
    @JsonProperty("price_amount") BigDecimal priceAmount,
    @JsonProperty("in_stock") boolean inStock,
    @JsonProperty("quantity") int quantity,
    @JsonProperty("origin_zone") String originZone,
    @JsonProperty("origin_pincode") String originPincode,
    @JsonProperty("seller_quality_score") float sellerQualityScore,
    @JsonProperty("heat_tolerance") String heatTolerance,
    @JsonProperty("max_transit_days") int maxTransitDays,
    @JsonProperty("size_tier") String sizeTier,
    @JsonProperty("light_requirement") String lightRequirement,
    @JsonProperty("pet_safe") Boolean petSafe,
    @JsonProperty("active") boolean active,
    @JsonProperty("indexed_at") String indexedAt
) {
}
