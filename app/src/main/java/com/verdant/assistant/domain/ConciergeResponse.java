package com.verdant.assistant.domain;

import java.util.List;
import java.util.UUID;

public record ConciergeResponse(Outcome outcome, String message, List<AvailableListing> listings,
                                 List<UUID> citations) {

    public static ConciergeResponse outOfScope(String message) {
        return new ConciergeResponse(Outcome.REFUSED_OUT_OF_SCOPE, message, List.of(), List.of());
    }

    public static ConciergeResponse noGuidance(String message, List<AvailableListing> fallback) {
        return new ConciergeResponse(Outcome.REFUSED_NO_RETRIEVAL, message, fallback, List.of());
    }

    public static ConciergeResponse nothingDeliverable(String message) {
        return new ConciergeResponse(Outcome.REFUSED_NO_RETRIEVAL, message, List.of(), List.of());
    }

    public static ConciergeResponse answered(List<AvailableListing> listings, List<UUID> citations, String message) {
        return new ConciergeResponse(Outcome.ANSWERED, message, listings, citations);
    }
}
