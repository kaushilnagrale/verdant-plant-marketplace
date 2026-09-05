package com.verdant.assistant.service;

import com.verdant.assistant.domain.DocType;
import com.verdant.assistant.domain.Scored;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Real vector search via pgvector's own cosine-distance operator (<=>),
 * the same real, established pattern HybridRetriever (Week 3) already
 * uses for canonical-product mapping - reused here rather than
 * reinvented. score() converts distance (0=identical) to similarity
 * (1=identical) since that's the more natural number for a similarity
 * floor to compare against (GiftConciergeService/PlantDoctorService).
 * Real lexical (FTS) fusion, like HybridRetriever's own RRF, is left out
 * of this first cut - this project's own "guardrails over the model"
 * thesis (61.1) is fully exercised by real vector retrieval alone; a
 * fusion step is a real, separate refinement, not a correctness gap.
 */
@Repository
public class KnowledgeChunkRepository {

    private final JdbcTemplate jdbc;

    public KnowledgeChunkRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void deleteByDocumentId(UUID documentId) {
        jdbc.update("DELETE FROM knowledge_chunks WHERE document_id = ?", documentId);
    }

    public void insert(UUID documentId, int chunkIndex, String heading, String text, float[] embedding) {
        jdbc.update("""
            INSERT INTO knowledge_chunks (document_id, chunk_index, heading, text, embedding)
            VALUES (?, ?, ?, ?, ?::vector)
            """, documentId, chunkIndex, heading, text, toVectorLiteral(embedding));
    }

    public int countWithNullEmbedding() {
        Integer count = jdbc.queryForObject(
            "SELECT count(*) FROM knowledge_chunks WHERE embedding IS NULL", Integer.class);
        return count == null ? 0 : count;
    }

    /**
     * Added for AssistantEvalTest (61.1): the response objects only carry
     * citation UUIDs, but the golden set's groundedness check needs the
     * actual cited TEXT to confirm retrieval landed on the right topic,
     * not just on a real, type-appropriate, non-fabricated chunk (already
     * guaranteed structurally by validate()/validateGrounded() - this is
     * checking something those don't: relevance, not just realness).
     */
    public String headingAndTextOf(UUID chunkId) {
        return jdbc.query("SELECT heading, text FROM knowledge_chunks WHERE id = ?",
                (rs, rowNum) -> (rs.getString("heading") == null ? "" : rs.getString("heading")) + " " + rs.getString("text"),
                chunkId)
            .stream().findFirst().orElse("");
    }

    public List<Scored> retrieve(float[] queryEmbedding, int limit, DocType... docTypes) {
        String vec = toVectorLiteral(queryEmbedding);
        String typeList = java.util.Arrays.stream(docTypes).map(t -> "'" + t.name() + "'")
            .collect(Collectors.joining(","));

        return jdbc.query("""
            SELECT c.id, c.document_id, d.doc_type, c.heading, c.text,
                   1 - (c.embedding <=> ?::vector) AS similarity
              FROM knowledge_chunks c
              JOIN knowledge_documents d ON d.id = c.document_id
             WHERE c.embedding IS NOT NULL AND d.active = true AND d.doc_type IN (%s)
             ORDER BY c.embedding <=> ?::vector
             LIMIT ?
            """.formatted(typeList),
            (rs, rowNum) -> Scored.of(
                (UUID) rs.getObject("id"),
                rs.getString("document_id"),
                DocType.valueOf(rs.getString("doc_type")),
                rs.getString("heading"),
                rs.getString("text"),
                rs.getDouble("similarity")),
            vec, vec, limit);
    }

    private String toVectorLiteral(float[] v) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(v[i]);
        }
        return sb.append(']').toString();
    }
}
