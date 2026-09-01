package com.verdant.cart;

import java.util.UUID;

/**
 * What a buyer's checkout request is allowed to say: which listing, how
 * many. Deliberately has no price field - a tampered client sending an
 * extra "price" in the JSON body has nowhere for it to bind to, so it is
 * silently dropped before it ever reaches CartPricingService. The server
 * always looks up the real price itself.
 */
public record CartLine(UUID listingId, int quantity) { }
