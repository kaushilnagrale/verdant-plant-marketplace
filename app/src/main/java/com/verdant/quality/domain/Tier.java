package com.verdant.quality.domain;

import java.math.BigDecimal;

/**
 * The runbook's own version of this enum is pseudocode - a Java enum with
 * constructor arguments needs an actual constructor and field
 * declarations, neither shown. Written out properly here.
 *
 * allocationMultiplier is BigDecimal, not double: this project's own
 * ArchitectureRulesTest bans float/double fields anywhere under
 * "..domain.." project-wide (Claim.faultConfidence hit the same rule,
 * Week 8) - broader than its own stated "money" reasoning, but a real,
 * deliberate, pre-existing safeguard, worth keeping intact rather than
 * carving an exception into for one enum. AllocationEngine (Day 54) does
 * the actual multiplication in plain double, same as everywhere else in
 * this class's own arithmetic - only the STORED field needs to be exact.
 *
 * "Never straight from a score to suspension" (53.3): these consequences
 * are reduced ranking/allocation share, nothing terminal - suspension
 * stays SellerOnboardingService's own human-actioned transition (Week 2),
 * untouched by this class.
 */
public enum Tier {
    PREFERRED(new BigDecimal("1.20"), Integer.MAX_VALUE),
    STANDARD(new BigDecimal("1.00"), Integer.MAX_VALUE),
    PROBATION(new BigDecimal("0.90"), 5),
    RESTRICTED(new BigDecimal("0.50"), 2);

    private final BigDecimal allocationMultiplier;
    private final int maxOpenOrders;

    Tier(BigDecimal allocationMultiplier, int maxOpenOrders) {
        this.allocationMultiplier = allocationMultiplier;
        this.maxOpenOrders = maxOpenOrders;
    }

    public BigDecimal allocationMultiplier() {
        return allocationMultiplier;
    }

    public int maxOpenOrders() {
        return maxOpenOrders;
    }
}
