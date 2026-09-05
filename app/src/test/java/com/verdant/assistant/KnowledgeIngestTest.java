package com.verdant.assistant;

import com.verdant.assistant.domain.DocType;
import com.verdant.assistant.service.KnowledgeChunkRepository;
import com.verdant.assistant.service.KnowledgeIngestService;
import com.verdant.common.testing.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real end to end: real markdown files on the real classpath, real
 * heading-based chunking, the real embedding model already used for
 * catalog mapping (Week 3), and a real pgvector similarity search
 * against what just got ingested - not mocked at any layer.
 */
class KnowledgeIngestTest extends IntegrationTestBase {

    @Autowired private KnowledgeIngestService ingestService;
    @Autowired private KnowledgeChunkRepository chunks;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private org.springframework.ai.embedding.EmbeddingModel embeddings;

    @Test
    void ingestAllFromClasspath_chunksEveryRealContentFile_withNoNullEmbeddings() {
        int ingested = ingestService.ingestAllFromClasspath();

        assertThat(ingested).isGreaterThanOrEqualTo(25);
        assertThat(chunks.countWithNullEmbedding()).isZero();

        Integer totalChunks = jdbc.queryForObject("SELECT count(*) FROM knowledge_chunks", Integer.class);
        assertThat(totalChunks).isGreaterThan(ingested); // every doc has multiple headings/chunks
    }

    @Test
    void chunk_splitsOnMarkdownHeadings_notFixedWindows() {
        String body = """
            Intro text before any heading.

            ## First heading
            Content of the first section.

            ## Second heading
            Content of the second section.
            """;

        var parts = ingestService.chunk(body);

        assertThat(parts).hasSize(3);
        assertThat(parts.get(0).heading()).isNull();
        assertThat(parts.get(1).heading()).isEqualTo("First heading");
        assertThat(parts.get(2).heading()).isEqualTo("Second heading");
    }

    @Test
    void reIngestingTheSameDocument_isIdempotent_doesNotDuplicateChunks() {
        ingestService.ingestAllFromClasspath();
        Integer firstCount = jdbc.queryForObject("SELECT count(*) FROM knowledge_chunks", Integer.class);

        ingestService.ingestAllFromClasspath();
        Integer secondCount = jdbc.queryForObject("SELECT count(*) FROM knowledge_chunks", Integer.class);

        assertThat(secondCount).isEqualTo(firstCount);
    }

    @Test
    void retrieve_findsARealSemanticMatch_forACareQuestion() {
        ingestService.ingestAllFromClasspath();

        var results = chunks.retrieve(
            embeddingFor("my money plant leaves are turning yellow, what should I do"),
            5, DocType.SYMPTOM_GUIDE, DocType.CARE_GUIDE);

        assertThat(results).isNotEmpty();
        assertThat(results.get(0).score().doubleValue()).isGreaterThan(0.3);
    }

    private float[] embeddingFor(String text) {
        return embeddings.embed(text);
    }
}
