package com.verdant.logistics;

import java.util.List;

/** Cheap to set on every label, measurably reduces damage claims for live plants. */
public final class ShipmentLabels {

    private ShipmentLabels() { }

    public static List<String> fragileLivePlant() {
        return List.of("FRAGILE", "THIS WAY UP", "PERISHABLE", "DO NOT STACK");
    }
}
