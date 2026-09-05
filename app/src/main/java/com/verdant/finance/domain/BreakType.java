package com.verdant.finance.domain;

public enum BreakType {
    /** Razorpay has a captured payment we have no matching order for - usually a missed webhook. */
    MISSING_INTERNAL,
    /** We have an order marked PAID that Razorpay's payments list for that day never returned. */
    MISSING_PROVIDER,
    /** Same payment on both sides, but the amounts disagree. */
    AMOUNT_MISMATCH,
    /** Same payment on both sides, but our order status doesn't match what Razorpay says happened to it. */
    STATUS_MISMATCH
}
