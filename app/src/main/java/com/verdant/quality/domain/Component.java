package com.verdant.quality.domain;

/**
 * One scored ingredient of a seller's quality score, with the raw counts
 * behind it - the whole point of storing this (not just a bare double) is
 * that a seller can see "8 of 10", not just "0.80" (53.2's own emphasis:
 * a number nobody can argue with is worse than one they can). numerator/
 * denominator are always the honest raw counts (e.g. "2 of 20 orders you
 * cancelled"), even for an inverted component - only value() (what
 * actually feeds the weighted score) flips for one built via inverse().
 */
public record Component(int numerator, int denominator, String description, boolean inverted) {

    /** A signal where MORE of the numerator is better (e.g. on-time dispatches). */
    public static Component of(int numerator, int denominator, String description) {
        return new Component(numerator, denominator, description, false);
    }

    /** A signal where MORE of the numerator is worse (e.g. cancellations, claims, RTOs) - value() reads as "how much of this did you avoid". */
    public static Component inverse(int numerator, int denominator, String description) {
        return new Component(numerator, denominator, description, true);
    }

    /** No data (denominator 0) reads as a perfect 1.0 either way - a seller with zero opportunities to fail a signal hasn't failed it. */
    public double value() {
        if (denominator == 0) {
            return 1.0;
        }
        double ratio = (double) numerator / denominator;
        return inverted ? 1.0 - ratio : ratio;
    }
}
