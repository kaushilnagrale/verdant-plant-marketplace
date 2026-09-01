package com.verdant.catalog.mapping;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

@Service
public class MappingService {

    /**
     * Titles containing any of these are accessory/decor/tool listings that
     * merely reference a plant's name or theme in their title ("Snake Plant
     * Leaf Pattern Cushion Cover", "Money Plant Support Stick") without being
     * the plant itself. Both lexical and vector search genuinely rank these
     * close to the real product - text similarity alone cannot tell "is a
     * plant" apart from "mentions a plant," so this is checked before
     * retrieval runs at all, forcing a match to UNMAPPED regardless of score.
     */
    private static final Set<String> DENYLIST_WORDS = Set.of(
        "cushion", "cover", "artificial", "fake", "decor", "garland",
        "macrame", "hanger", "mala", "beads", "trowel", "cultivator",
        "gloves", "claws", "skewers", "pole", "vermicompost", "manure",
        "fertilizer", "seaweed", "empty", "stand"
    );

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
        if (containsDenylistedWord(normalized.text())) return MappingResult.unmapped();

        List<HybridRetriever.Scored> hits = retriever.retrieve(normalized.text(), 3);
        if (hits.isEmpty()) return MappingResult.unmapped();

        var best = hits.get(0);

        if (best.score() >= autoThreshold)   return MappingResult.autoMapped(best);
        if (best.score() >= reviewThreshold) return MappingResult.needsReview(hits);
        return MappingResult.unmapped();
    }

    private boolean containsDenylistedWord(String normalizedText) {
        for (String word : normalizedText.split("\\s+")) {
            if (DENYLIST_WORDS.contains(word)) return true;
        }
        return false;
    }
}
