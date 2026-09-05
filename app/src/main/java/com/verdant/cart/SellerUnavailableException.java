package com.verdant.cart;

import java.util.UUID;

/**
 * Search may be stale (Week 9) - a listing's seller may have been
 * suspended since the index was last refreshed. Thrown by
 * CartPricingService, which re-checks the real seller row every time,
 * never the search index. Deliberately keyed on plain APPROVED status,
 * matching CandidateFinder's own existing, already-tested allocation
 * rule (Week 6) - not the stricter SellerCapabilities.canReceiveOrders()
 * (which also requires bank verification, a separate, later-in-onboarding
 * gate on PAYOUTS specifically, not on being sold to at all). Keeping
 * this check the same as CandidateFinder's avoids a confusing mismatch
 * where a cart quote could refuse a seller that checkout's own
 * allocation would have happily accepted.
 */
public class SellerUnavailableException extends RuntimeException {
    public SellerUnavailableException(UUID sellerId) {
        super("Seller unavailable: " + sellerId);
    }
}
