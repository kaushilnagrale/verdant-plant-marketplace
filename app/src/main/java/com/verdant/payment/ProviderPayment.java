package com.verdant.payment;

import com.verdant.common.domain.Money;

import java.time.Instant;

/**
 * What Razorpay says actually happened to a payment, independent of what
 * our own orders table thinks - the whole point of reconciliation (Day 46)
 * is comparing this against our internal state, so this type deliberately
 * carries nothing beyond what the provider itself reports.
 */
public record ProviderPayment(
    String paymentId,
    String orderId,
    Money amount,
    String status,
    Instant capturedAt
) {
}
