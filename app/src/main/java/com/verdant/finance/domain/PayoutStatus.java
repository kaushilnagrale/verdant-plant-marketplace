package com.verdant.finance.domain;

/** Matches the payout_cycles.status CHECK constraint (V30). */
public enum PayoutStatus {
    DRAFT, PENDING_APPROVAL, APPROVED, INITIATED, PAID, FAILED, CANCELLED
}
