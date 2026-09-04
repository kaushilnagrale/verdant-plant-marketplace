package com.verdant.claims.service;

import java.util.UUID;

/** Unchecked so it rolls back the enclosing transaction, same reasoning as InsufficientStockException. */
public class ClaimWindowClosedException extends RuntimeException {
    public ClaimWindowClosedException(UUID orderItemId) {
        super("No open claim window for order item " + orderItemId);
    }
}
