package com.verdant.inventory.domain;

public final class InventoryPolicy {

    private InventoryPolicy() { }

    /**
     * Sellable quantity holds back a buffer to absorb offline sales the
     * platform has not seen yet. Listing auto-pauses at or below the buffer.
     */
    public static int sellable(int quantity, int buffer) {
        return Math.max(0, quantity - buffer);
    }

    public static boolean shouldAutoPause(int quantity, int buffer) {
        return quantity <= buffer;
    }
}
