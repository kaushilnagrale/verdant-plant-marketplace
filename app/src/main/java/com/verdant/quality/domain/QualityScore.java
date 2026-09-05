package com.verdant.quality.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.UUID;

/** value is BigDecimal, not double - this project's own ArchitectureRulesTest bans float/double fields anywhere under "..domain.." (Claim.faultConfidence hit the same rule, Week 8); see Tier's own comment for the full reasoning. */
public record QualityScore(
    UUID sellerId,
    BigDecimal value,
    Map<String, Component> components,
    int orderCount,
    boolean isProvisional,
    Tier tier
) {
    /** Constructed from the scorer's own plain-double arithmetic - scaled to 4 places, more than enough resolution for a 0.00-1.00 score while keeping the stored field exact rather than a raw double's binary approximation. */
    public static QualityScore of(UUID sellerId, double value, Map<String, Component> components,
                                   int orderCount, boolean isProvisional, Tier tier) {
        return new QualityScore(sellerId, BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP),
            components, orderCount, isProvisional, tier);
    }
}
