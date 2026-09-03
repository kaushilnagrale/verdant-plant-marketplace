package com.verdant.logistics.weather;

/**
 * Matches canonical_products.heat_tolerance (LOW/MEDIUM/HIGH, already
 * exists). Thresholds are a judgment call, not a botanical fact - a real
 * platform would tune these from real DOA (dead-on-arrival) outcomes, the
 * same way Day 42's shadow-mode observation period is meant to.
 */
public enum HeatTolerance {
    LOW(34.0),
    MEDIUM(38.0),
    HIGH(42.0);

    private final double maxSafeTempC;

    HeatTolerance(double maxSafeTempC) {
        this.maxSafeTempC = maxSafeTempC;
    }

    public double maxSafeTempC() {
        return maxSafeTempC;
    }

    public static HeatTolerance parse(String raw) {
        return HeatTolerance.valueOf(raw);
    }
}
