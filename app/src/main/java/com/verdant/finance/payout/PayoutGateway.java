package com.verdant.finance.payout;

import com.verdant.common.domain.Money;

import java.util.UUID;

/**
 * Only the network-calling part of actually moving money to a seller's
 * bank account goes behind this interface - same shape as PaymentGateway/
 * CourierGateway.
 *
 * Stub-only, by explicit decision: unlike Razorpay (checkout) and
 * Shiprocket (courier), there is no real payout provider account for this
 * project (would need RazorpayX or similar), and no real bank account to
 * send money to regardless - this whole platform has no real business
 * behind it (see LAUNCH_CHECKLIST.md). The ledger accounting, eligibility
 * rules, and reconciliation this week are all real and fully tested; the
 * physical bank transfer is the one piece that's honestly simulated,
 * because there is nothing real on the other end of it to verify against.
 */
public interface PayoutGateway {
    /** Initiates a bank transfer and returns the provider's own reference for it. */
    String transfer(String idempotencyKey, UUID sellerId, Money amount);
}
