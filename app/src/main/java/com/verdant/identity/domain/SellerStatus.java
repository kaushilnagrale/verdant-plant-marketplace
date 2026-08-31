package com.verdant.identity.domain;

import java.util.Map;
import java.util.Set;

public enum SellerStatus {
    DRAFT, PENDING_REVIEW, APPROVED, SUSPENDED, OFFBOARDED;

    private static final Map<SellerStatus, Set<SellerStatus>> ALLOWED = Map.of(
        DRAFT,          Set.of(PENDING_REVIEW, OFFBOARDED),
        PENDING_REVIEW, Set.of(APPROVED, DRAFT, OFFBOARDED),
        APPROVED,       Set.of(SUSPENDED, OFFBOARDED),
        SUSPENDED,      Set.of(APPROVED, OFFBOARDED),
        OFFBOARDED,     Set.of()
    );

    public boolean canMoveTo(SellerStatus next) {
        return ALLOWED.getOrDefault(this, Set.of()).contains(next);
    }

    public void assertCanMoveTo(SellerStatus next) {
        if (!canMoveTo(next))
            throw new IllegalStateTransitionException(this, next);
    }
}
