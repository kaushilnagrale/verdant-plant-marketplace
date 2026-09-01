package com.verdant.catalog.mapping;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class HybridRetriever {

    private static final int RRF_K = 60;

    private final JdbcTemplate jdbc;
    private final EmbeddingModel embeddings;

    /**
     * Cosine distance ceiling (0 = identical, 2 = opposite). Vector search
     * always returns *something* as "closest" among however many rows exist,
     * even for a completely unrelated title - this floor rejects candidates
     * that only won by default, not because they're actually close.
     */
    @Value("${verdant.mapping.vector-similarity-floor:0.6}")
    private double similarityFloor;

    public HybridRetriever(JdbcTemplate jdbc, EmbeddingModel embeddings) {
        this.jdbc = jdbc;
        this.embeddings = embeddings;
    }

    public List<Scored> retrieve(String normalizedTitle, int k) {
        List<UUID> vectorHits  = vectorSearch(normalizedTitle, k * 2);
        List<UUID> lexicalHits = lexicalSearch(normalizedTitle, k * 2);
        return fuse(vectorHits, lexicalHits).stream().limit(k).toList();
    }

    private List<UUID> vectorSearch(String text, int limit) {
        String vec = toVectorLiteral(embeddings.embed(text));
        return jdbc.queryForList("""
            SELECT id FROM canonical_products
             WHERE embedding IS NOT NULL
               AND embedding <=> ?::vector < ?
             ORDER BY embedding <=> ?::vector
             LIMIT ?
            """, UUID.class, vec, similarityFloor, vec, limit);
    }

    private List<UUID> lexicalSearch(String text, int limit) {
        // Uses immutable_product_search_text(...) so this matches the
        // canonical_products_fts GIN index expression exactly (V5) and
        // is actually served by the index rather than a full table scan.
        //
        // OR (|), not plainto_tsquery's implicit AND: a real listing title
        // has several words irrelevant to the plant itself ("asli", "with
        // glass bowl"), and requiring every word to match sinks the whole
        // query to zero results even when the one word that matters
        // ("sansevieria") is right there.
        String orQuery = String.join(" | ", text.trim().split("\\s+"));
        if (orQuery.isBlank()) return List.of();

        return jdbc.queryForList("""
            SELECT id FROM canonical_products
             WHERE immutable_product_search_text(display_name, botanical_name, common_names)
                   @@ to_tsquery('simple', ?)
             ORDER BY ts_rank(
                   immutable_product_search_text(display_name, botanical_name, common_names),
                   to_tsquery('simple', ?)) DESC
             LIMIT ?
            """, UUID.class, orQuery, orQuery, limit);
    }

    /** Reciprocal rank fusion: uses rank position, not raw score. */
    static List<Scored> fuse(List<UUID> a, List<UUID> b) {
        Map<UUID, Double> scores = new HashMap<>();
        for (int i = 0; i < a.size(); i++) scores.merge(a.get(i), 1.0 / (RRF_K + i + 1), Double::sum);
        for (int i = 0; i < b.size(); i++) scores.merge(b.get(i), 1.0 / (RRF_K + i + 1), Double::sum);

        return scores.entrySet().stream()
            .sorted(Map.Entry.<UUID, Double>comparingByValue().reversed())
            .map(e -> new Scored(e.getKey(), e.getValue()))
            .toList();
    }

    private String toVectorLiteral(float[] v) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) { if (i > 0) sb.append(','); sb.append(v[i]); }
        return sb.append(']').toString();
    }

    public record Scored(UUID canonicalProductId, double score) { }
}
