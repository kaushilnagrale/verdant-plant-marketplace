package com.verdant.payment;

import com.verdant.common.domain.Money;

/**
 * Only the network-calling part of talking to a payment provider goes behind
 * this interface. Signature verification (checkout callback + webhook) is
 * pure HMAC math with no network call, so it is tested directly against the
 * real com.razorpay.Utils rather than through a stub.
 */
public interface PaymentGateway {
    /** Creates a payment order with the gateway and returns its gateway-side order id. */
    String createOrder(Money amount, String receiptId);
}
