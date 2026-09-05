package com.verdant.cart;

import java.util.UUID;

/**
 * Search may be stale (Week 9) - a listing a buyer found through it may
 * have been deactivated since the index was last refreshed. Thrown by
 * CartPricingService, which re-checks the real listing row every time,
 * never the search index.
 */
public class ListingNoLongerAvailableException extends RuntimeException {
    public ListingNoLongerAvailableException(UUID listingId) {
        super("Listing no longer available: " + listingId);
    }
}
