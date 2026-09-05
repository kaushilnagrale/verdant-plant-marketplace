package com.verdant.assistant.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class KnowledgeDocumentRepository {

    private final JdbcTemplate jdbc;

    public KnowledgeDocumentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Idempotent by title - re-ingesting the same content/knowledge/*.md files (a real, repeatable admin action) updates the existing row rather than duplicating it. */
    public UUID upsertByTitle(String docType, String title, String body, String occasion, String author) {
        List<UUID> existing = jdbc.queryForList(
            "SELECT id FROM knowledge_documents WHERE title = ?", UUID.class, title);
        if (!existing.isEmpty()) {
            UUID id = existing.get(0);
            jdbc.update("""
                UPDATE knowledge_documents SET doc_type = ?, body = ?, occasion = ?, author = ? WHERE id = ?
                """, docType, body, occasion, author, id);
            return id;
        }
        return jdbc.queryForObject("""
            INSERT INTO knowledge_documents (doc_type, title, body, occasion, author)
            VALUES (?, ?, ?, ?, ?) RETURNING id
            """, UUID.class, docType, title, body, occasion, author);
    }

    public List<UUID> allIds() {
        return jdbc.queryForList("SELECT id FROM knowledge_documents WHERE active = true", UUID.class);
    }

    public Map<String, Object> findById(UUID id) {
        return jdbc.queryForMap("SELECT id, doc_type, title, body FROM knowledge_documents WHERE id = ?", id);
    }

    public List<Map<String, Object>> countsByDocType() {
        return jdbc.queryForList("""
            SELECT d.doc_type, count(c.id) AS chunks
              FROM knowledge_documents d LEFT JOIN knowledge_chunks c ON c.document_id = d.id
             GROUP BY d.doc_type
            """);
    }
}
