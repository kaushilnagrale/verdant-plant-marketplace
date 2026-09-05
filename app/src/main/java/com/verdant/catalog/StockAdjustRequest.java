package com.verdant.catalog;

/** reason must be one of inventory_adjustments' own real CHECK constraint values (V11) - free text isn't accepted, matching how every other real adjustment in this project (a sale, a released reservation) is already recorded. */
public record StockAdjustRequest(int delta, String reason) { }
