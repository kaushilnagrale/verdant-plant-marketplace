package com.verdant.search.query;

import java.math.BigDecimal;
import java.util.UUID;

public record SearchHit(
    UUID listingId,
    UUID sellerId,
    String displayName,
    BigDecimal priceAmount,
    boolean inStock,
    String originZone,
    float score
) {
}
