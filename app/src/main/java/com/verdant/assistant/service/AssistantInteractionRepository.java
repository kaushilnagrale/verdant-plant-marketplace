package com.verdant.assistant.service;

import com.verdant.assistant.domain.Outcome;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The real production evidence 61.3 is built around - refusal rate, citation rate, and latency all become measurable from this table, not asserted from memory. */
@Repository
public class AssistantInteractionRepository {

    private final JdbcTemplate jdbc;

    public AssistantInteractionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * cited_chunk_ids is a real Postgres UUID[] column - plain
     * JdbcTemplate has no clean way to bind a raw Java array as one
     * (the same real limitation ProjectionQueryRepository's own doc
     * comment already notes, Week 9), so this drops into
     * connection.createArrayOf() directly via a ConnectionCallback
     * rather than a plain update() call.
     */
    public void record(String feature, String userSubjectId, String question, Outcome outcome,
                        List<UUID> citedChunkIds, Double topSimilarity, long latencyMs) {
        jdbc.execute((java.sql.Connection con) -> {
            java.sql.Array citedArray = citedChunkIds.isEmpty() ? null
                : con.createArrayOf("uuid", citedChunkIds.toArray());
            try (var ps = con.prepareStatement("""
                INSERT INTO assistant_interactions
                    (feature, user_subject_id, question, outcome, cited_chunk_ids, top_similarity, latency_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """)) {
                ps.setString(1, feature);
                ps.setString(2, userSubjectId);
                ps.setString(3, question);
                ps.setString(4, outcome.name());
                ps.setArray(5, citedArray);
                if (topSimilarity == null) {
                    ps.setNull(6, java.sql.Types.REAL);
                } else {
                    ps.setDouble(6, topSimilarity);
                }
                ps.setInt(7, (int) latencyMs);
                ps.executeUpdate();
                return null;
            }
        });
    }

    public Map<String, Object> latest() {
        return jdbc.queryForList("SELECT * FROM assistant_interactions ORDER BY created_at DESC LIMIT 1")
            .stream().findFirst().orElseThrow();
    }
}
