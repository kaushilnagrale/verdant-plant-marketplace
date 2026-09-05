package com.verdant.payment;

import com.verdant.common.domain.Money;

import java.time.LocalDate;
import java.util.List;

/**
 * Only the network-calling part of talking to a payment provider goes behind
 * this interface. Signature verification (checkout callback + webhook) is
 * pure HMAC math with no network call, so it is tested directly against the
 * real com.razorpay.Utils rather than through a stub.
 */
public interface PaymentGateway {
    /** Creates a payment order with the gateway and returns its gateway-side order id. */
    String createOrder(Money amount, String receiptId);

    /** Refunds a captured payment (saga compensation) and returns the gateway-side refund id. */
    String refund(String paymentId, Money amount);

    /**
     * Every payment Razorpay actually captured for the given calendar day
     * (IST) - the ground truth for reconciliation (Day 46).
     *
     * Deliberately NOT called "settlementReport": Razorpay's real
     * Settlements API is a different concept entirely - it reports batched
     * payouts of accumulated funds INTO the merchant's own bank account,
     * days later and grouped across many payments (T+2/T+3 in practice),
     * not individual payment-level detail for a given day. What answers
     * "did a payment succeed at Razorpay but never reach us" is the plain
     * Payments list API (GET /v1/payments), which is what this method
     * actually calls.
     */
    List<ProviderPayment> paymentsCapturedOn(LocalDate date);
}
