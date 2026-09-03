package com.verdant.orders.allocation;

import java.util.UUID;

/**
 * One seller's listing that could fulfill a cart line - not necessarily the
 * listing the buyer actually clicked. Any seller stocking the same
 * canonical product is a candidate; the engine picks the best one.
 */
public record Candidate(
    UUID sellerId,
    UUID listingId,
    String productName,
    int availableQuantity,
    int transitDays,
    int maxSafeTransitDays,
    double sellerQualityScore,
    double normalizedPrice,
    boolean sellerCanReceiveOrders
) {
    public boolean hasStock(int quantity) {
        return availableQuantity >= quantity;
    }

    public boolean canReceiveOrders() {
        return sellerCanReceiveOrders;
    }
}
