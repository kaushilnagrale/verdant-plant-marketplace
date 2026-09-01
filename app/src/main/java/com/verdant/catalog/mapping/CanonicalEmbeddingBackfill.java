package com.verdant.catalog.mapping;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class CanonicalEmbeddingBackfill {

    private final JdbcTemplate jdbc;
    private final EmbeddingModel embeddings;

    public CanonicalEmbeddingBackfill(JdbcTemplate jdbc, EmbeddingModel embeddings) {
        this.jdbc = jdbc;
        this.embeddings = embeddings;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void backfill() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT id, display_name, botanical_name, common_names
              FROM canonical_products
             WHERE embedding IS NULL
            """);

        for (Map<String, Object> row : rows) {
            String text = buildEmbeddingText(row);
            float[] vector = embeddings.embed(text);
            jdbc.update("UPDATE canonical_products SET embedding = ?::vector WHERE id = ?",
                toVectorLiteral(vector), (UUID) row.get("id"));
        }
    }

    /** Embed every name variant together so any of them retrieves the product. */
    private String buildEmbeddingText(Map<String, Object> row) {
        String common;
        try {
            common = String.join(" ", (String[]) ((java.sql.Array) row.get("common_names")).getArray());
        } catch (java.sql.SQLException e) {
            throw new RuntimeException("Failed to read common_names array", e);
        }
        return row.get("display_name") + " " + row.get("botanical_name") + " " + common;
    }

    private String toVectorLiteral(float[] v) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) { if (i > 0) sb.append(','); sb.append(v[i]); }
        return sb.append(']').toString();
    }
}
