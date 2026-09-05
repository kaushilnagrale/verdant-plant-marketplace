package com.verdant.forecasting.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.UUID;

/**
 * trendFactor is a plain ratio, not money - but ArchitectureRulesTest's
 * money_never_uses_floating_point rule bans double/float FIELDS anywhere
 * under a ..domain.. package regardless of what they represent (already
 * hit for QualityScore/Tier, Week 10, and Scored, Week 11) - same fix,
 * applied up front this time rather than discovered by a failing build:
 * BigDecimal at this boundary, plain double for the arithmetic inside
 * DemandForecaster itself.
 */
public record Forecast(UUID canonicalProductId, LocalDate asOfDate, int baselineUnits, BigDecimal trendFactor,
                        boolean festivalBoosted, String festivalName, int forecastUnits) {

    public static Forecast of(UUID canonicalProductId, LocalDate asOfDate, int baselineUnits, double trendFactor,
                               boolean festivalBoosted, String festivalName, int forecastUnits) {
        return new Forecast(canonicalProductId, asOfDate, baselineUnits,
            BigDecimal.valueOf(trendFactor).setScale(3, RoundingMode.HALF_UP),
            festivalBoosted, festivalName, forecastUnits);
    }
}
