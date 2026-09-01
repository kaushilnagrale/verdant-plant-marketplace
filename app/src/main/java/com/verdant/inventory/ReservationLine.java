package com.verdant.inventory;

import java.util.UUID;

/** One listing + quantity to reserve as part of a multi-item checkout. */
public record ReservationLine(UUID listingId, int quantity) { }
