package com.verdant.logistics.domain;

import org.springframework.stereotype.Component;

/**
 * A potted plant is bulky and light - couriers bill on volumetric weight
 * (the space a parcel occupies, not what it weighs), and charging dead
 * weight loses margin silently on every single order.
 */
@Component
public class WeightCalculator {

    private static final int VOLUMETRIC_DIVISOR = 5000; // standard surface courier divisor

    public Weight calculate(int lengthCm, int widthCm, int heightCm, int deadWeightGrams) {
        int volumetricGrams = (lengthCm * widthCm * heightCm * 1000) / VOLUMETRIC_DIVISOR;
        return new Weight(deadWeightGrams, volumetricGrams,
                          Math.max(deadWeightGrams, volumetricGrams));
    }

    public record Weight(int deadGrams, int volumetricGrams, int billableGrams) { }
}
