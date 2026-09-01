package com.verdant.catalog.mapping;

import java.util.List;
import java.util.UUID;

public record MappingResult(
        Status status,
        UUID canonicalProductId,
        double confidence,
        List<HybridRetriever.Scored> candidates) {

    public enum Status { AUTO_MAPPED, PENDING_REVIEW, UNMAPPED }

    public static MappingResult unmapped() {
        return new MappingResult(Status.UNMAPPED, null, 0.0, List.of());
    }

    public static MappingResult autoMapped(HybridRetriever.Scored best) {
        return new MappingResult(Status.AUTO_MAPPED, best.canonicalProductId(), best.score(), List.of(best));
    }

    public static MappingResult needsReview(List<HybridRetriever.Scored> candidates) {
        return new MappingResult(Status.PENDING_REVIEW, null,
            candidates.isEmpty() ? 0.0 : candidates.get(0).score(), candidates);
    }
}
