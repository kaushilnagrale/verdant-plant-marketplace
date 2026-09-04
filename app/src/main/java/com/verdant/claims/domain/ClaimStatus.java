package com.verdant.claims.domain;

/** Matches the claims.status CHECK constraint (V28). */
public enum ClaimStatus {
    SUBMITTED, UNDER_REVIEW, APPROVED, REJECTED, RESOLVED
}
