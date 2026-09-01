package com.verdant.inventory;

import java.util.UUID;

/** Unchecked so a thrown instance rolls back the enclosing @Transactional reserveAll(). */
public class InsufficientStockException extends RuntimeException {
    public InsufficientStockException(UUID listingId) {
        super("Insufficient stock for listing " + listingId);
    }
}
