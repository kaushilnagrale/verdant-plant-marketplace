package com.verdant.checkout;

import com.verdant.cart.CartLine;

import java.util.List;

/**
 * destinationPincode drives real allocation now, not just the pre-payment
 * preview (see CheckoutService) - a real order needs to know where it's
 * shipping before it can pick which seller fulfills each line.
 */
public record CheckoutRequest(String buyerEmail, List<CartLine> lines, String destinationPincode) { }
