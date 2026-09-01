package com.verdant.catalog.mapping;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class MappingService {

    private final TitleNormalizer normalizer;
    private final HybridRetriever retriever;

    @Value("${verdant.mapping.auto-threshold:0.030}")
    private double autoThreshold;

    @Value("${verdant.mapping.review-threshold:0.012}")
    private double reviewThreshold;

    public MappingService(TitleNormalizer normalizer, HybridRetriever retriever) {
        this.normalizer = normalizer;
        this.retriever = retriever;
    }

    public MappingResult map(String rawTitle, String description) {
        var normalized = normalizer.normalize(
            rawTitle + (description == null ? "" : " " + description));

        if (normalized.text().isBlank()) return MappingResult.unmapped();

        List<HybridRetriever.Scored> hits = retriever.retrieve(normalized.text(), 3);
        if (hits.isEmpty()) return MappingResult.unmapped();

        var best = hits.get(0);

        if (best.score() >= autoThreshold)   return MappingResult.autoMapped(best);
        if (best.score() >= reviewThreshold) return MappingResult.needsReview(hits);
        return MappingResult.unmapped();
    }
}
