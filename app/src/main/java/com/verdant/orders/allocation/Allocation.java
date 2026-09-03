package com.verdant.orders.allocation;

import java.util.UUID;

public record Allocation(UUID listingId, UUID sellerId, String productName,
                          int quantity, int transitDays, int maxSafeTransitDays, double score) {

    public static Allocation of(com.verdant.cart.CartLine line, Candidate chosen, double score) {
        return new Allocation(chosen.listingId(), chosen.sellerId(), chosen.productName(),
            line.quantity(), chosen.transitDays(), chosen.maxSafeTransitDays(), score);
    }
}
