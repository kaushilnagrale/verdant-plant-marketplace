package com.verdant.claims.service;

import java.util.UUID;

public class InsufficientEvidenceException extends RuntimeException {
    public InsufficientEvidenceException(UUID orderItemId) {
        super("At least one photo is required to submit a claim for order item " + orderItemId);
    }
}
