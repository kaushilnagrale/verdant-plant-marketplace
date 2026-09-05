package com.verdant.assistant.domain;

import java.math.BigDecimal;
import java.util.UUID;

/** A listing that is genuinely in stock, from an approved and non-vacationing seller, and can be delivered alive to the buyer's own destination within its own real max_transit_days (AvailabilityService) - the ONLY things a real recommendation is ever allowed to name. */
public record AvailableListing(UUID id, UUID sellerId, String productName, BigDecimal priceAmount, int transitDays) {
}
