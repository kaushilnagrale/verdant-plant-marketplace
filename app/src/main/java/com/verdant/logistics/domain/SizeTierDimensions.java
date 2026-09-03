package com.verdant.logistics.domain;

import java.util.Map;

/**
 * Not in the runbook, and not real per-listing data - nothing in this
 * project captures an actual listing's packed dimensions or weight (no
 * seller has ever been asked to enter one). Approximated from
 * canonical_products.size_tier (SMALL/MEDIUM/LARGE/SPECIMEN, already
 * exists) instead: a typical packed potted-plant parcel for each tier.
 * A real platform would ask sellers for real dimensions at listing time
 * and use WeightCalculator on the real numbers - this is a documented
 * stand-in, not a claim of precision, kept only so ShipmentBookingService
 * has something real to compute a billable weight from.
 */
public final class SizeTierDimensions {

    public record Dimensions(int lengthCm, int widthCm, int heightCm, int deadWeightGrams) { }

    private static final Map<String, Dimensions> BY_TIER = Map.of(
        "SMALL",    new Dimensions(15, 15, 20, 500),
        "MEDIUM",   new Dimensions(25, 25, 35, 1200),
        "LARGE",    new Dimensions(35, 35, 50, 2500),
        "SPECIMEN", new Dimensions(50, 50, 80, 6000)
    );

    private SizeTierDimensions() { }

    public static Dimensions forTier(String sizeTier) {
        Dimensions d = BY_TIER.get(sizeTier);
        if (d == null) {
            throw new IllegalArgumentException("Unknown size_tier: " + sizeTier);
        }
        return d;
    }
}
