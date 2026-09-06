package com.verdant.checkout;

import com.verdant.cart.CartLine;

import java.util.List;

/**
 * destinationPincode drives real allocation (see CheckoutService) - a
 * real order needs to know where it's shipping before it can pick which
 * seller fulfills each line. The rest of the address (recipientName/
 * recipientPhone/addressLine1/addressLine2/city/state) doesn't affect
 * allocation at all - pincode alone does - but a real shipment can't be
 * booked without it, and there was nowhere to put it before buyer
 * accounts existed. buyerEmail stays a request field (used for the
 * order record/receipts) even though the buyer's real identity now
 * comes from the JWT, not this field - CheckoutController resolves and
 * passes the authenticated buyer id separately.
 */
public record CheckoutRequest(String buyerEmail, List<CartLine> lines, String destinationPincode,
                               String recipientName, String recipientPhone,
                               String addressLine1, String addressLine2, String city, String state) {

    /**
     * Convenience constructor for the many existing tests focused on
     * something other than the address itself (saga compensation,
     * reconciliation, dispatch orchestration, payment verification, ...)
     * - fills in a real-shaped, clearly-a-fixture address rather than
     * forcing every one of those call sites to spell out an address that
     * has nothing to do with what they're actually testing. The real,
     * production path (CheckoutController) always uses the canonical
     * constructor with a real buyer-entered address - this one never
     * runs outside a test.
     */
    public CheckoutRequest(String buyerEmail, List<CartLine> lines, String destinationPincode) {
        this(buyerEmail, lines, destinationPincode, "Test Buyer", "9999999999", "123 Test Street", null, "Test City", "Test State");
    }
}
