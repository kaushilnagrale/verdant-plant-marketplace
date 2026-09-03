package com.verdant.logistics.weather;

/** Matches the dispatch_holds.reason CHECK constraint (V25). */
public enum HoldReason {
    HEAT,
    RAIN,
    FLOOD,
    COURIER_DISRUPTION,
    SELLER_UNAVAILABLE,
    MANUAL
}
