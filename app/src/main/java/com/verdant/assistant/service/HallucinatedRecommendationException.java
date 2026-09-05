package com.verdant.assistant.service;

import java.util.List;
import java.util.UUID;

/** The model named a listing that was never in the real, deterministic AVAILABLE list it was given - a hallucination becomes an exception here, not a bad customer experience (59.1's own framing). */
public class HallucinatedRecommendationException extends RuntimeException {
    public HallucinatedRecommendationException(List<UUID> listingIds) {
        super("Model recommended listing id(s) never offered to it: " + listingIds);
    }
}
