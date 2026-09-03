package com.verdant.logistics.weather;

import java.time.LocalDate;

public record DispatchDecision(boolean hold, HoldReason reason, String forecastSnapshot, LocalDate maxHoldUntil) {

    public static DispatchDecision proceed() {
        return new DispatchDecision(false, null, null, null);
    }

    public static DispatchDecision hold(HoldReason reason, String forecastSnapshot, LocalDate maxHoldUntil) {
        return new DispatchDecision(true, reason, forecastSnapshot, maxHoldUntil);
    }

    public boolean isHold() { return hold; }
    public boolean isProceed() { return !hold; }
}
