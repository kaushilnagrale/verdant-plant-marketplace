package com.verdant.assistant.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Chunks on markdown headings, not fixed token windows (58.3's own
 * emphasis) - a chunk that splits mid-advice retrieves as a fragment
 * that means something different from the whole. Content lives in
 * content/knowledge/*.md at the repo root (the real authoring location -
 * matches the runbook's own path) and is copied into
 * app/src/main/resources/knowledge/ for real runtime loading from the
 * classpath - the same real sync-point pattern this project's search
 * index mapping already established (SearchIntegrationTestBase's own
 * copy of infra/opensearch/listings-index.json), documented there for
 * the same reason: a packaged jar needs its content bundled inside it,
 * not read from a path that only exists in this one dev checkout.
 *
 * Real embedding model reused directly from Week 3
 * (org.springframework.ai.embedding.EmbeddingModel, the same bean
 * HybridRetriever already uses for canonical-product mapping) - already
 * produces real 768-dim vectors, exactly matching this week's own schema,
 * so no second embedding pipeline was needed.
 */
@Service
public class KnowledgeIngestService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeIngestService.class);
    private static final int MAX_CHUNK_CHARS = 1500;
    private static final Pattern FRONTMATTER = Pattern.compile("(?s)^---\\n(.*?)\\n---\\n(.*)$");
    private static final Pattern HEADING = Pattern.compile("(?m)^##\\s+(.+)$");

    private final KnowledgeDocumentRepository documents;
    private final KnowledgeChunkRepository chunks;
    private final EmbeddingModel embeddings;

    public KnowledgeIngestService(KnowledgeDocumentRepository documents, KnowledgeChunkRepository chunks,
                                   EmbeddingModel embeddings) {
        this.documents = documents;
        this.chunks = chunks;
        this.embeddings = embeddings;
    }

    public record Section(String heading, String text) { }
    public record Chunk(String heading, String text) { }

    /** Splits on "## Heading" lines - a section too long for one chunk is further split with overlap, so a retrieved fragment still carries enough of its own neighboring context to stand alone. */
    public List<Chunk> chunk(String body) {
        List<Section> sections = splitByHeading(body);
        List<Chunk> result = new ArrayList<>();
        for (Section s : sections) {
            if (s.text().length() > MAX_CHUNK_CHARS) {
                result.addAll(splitWithOverlap(s));
            } else {
                result.add(new Chunk(s.heading(), s.text()));
            }
        }
        return result;
    }

    private List<Section> splitByHeading(String body) {
        List<Section> sections = new ArrayList<>();
        Matcher m = HEADING.matcher(body);
        int lastEnd = 0;
        String lastHeading = null;
        while (m.find()) {
            if (lastHeading != null || lastEnd > 0) {
                addIfNonBlank(sections, lastHeading, body.substring(lastEnd, m.start()));
            } else if (m.start() > 0) {
                addIfNonBlank(sections, null, body.substring(0, m.start()));
            }
            lastHeading = m.group(1).trim();
            lastEnd = m.end();
        }
        addIfNonBlank(sections, lastHeading, body.substring(lastEnd));
        return sections;
    }

    private void addIfNonBlank(List<Section> sections, String heading, String text) {
        String trimmed = text.strip();
        if (!trimmed.isEmpty()) {
            sections.add(new Section(heading, trimmed));
        }
    }

    /** 15% overlap between adjacent pieces of an over-long section - enough that a fact right at a split boundary is very unlikely to be lost from both neighboring chunks at once. */
    private List<Chunk> splitWithOverlap(Section s) {
        List<Chunk> parts = new ArrayList<>();
        int overlap = (int) (MAX_CHUNK_CHARS * 0.15);
        int start = 0;
        while (start < s.text().length()) {
            int end = Math.min(s.text().length(), start + MAX_CHUNK_CHARS);
            parts.add(new Chunk(s.heading(), s.text().substring(start, end)));
            if (end == s.text().length()) break;
            start = end - overlap;
        }
        return parts;
    }

    /** Re-ingest is idempotent: existing chunks for this document are cleared first, so a content edit never leaves stale chunks retrievable alongside the new ones. */
    public void ingest(UUID documentId) {
        Map<String, Object> doc = documents.findById(documentId);
        String title = (String) doc.get("title");
        String body = (String) doc.get("body");
        chunks.deleteByDocumentId(documentId);

        List<Chunk> parts = chunk(body);
        for (int i = 0; i < parts.size(); i++) {
            Chunk c = parts.get(i);
            // Heading embedded with the body so a heading-only query still retrieves.
            float[] vector = embeddings.embed(title + " " + (c.heading() == null ? "" : c.heading()) + " " + c.text());
            chunks.insert(documentId, i, c.heading(), c.text(), vector);
        }
    }

    /** The real admin action (KnowledgeIngestController) - loads every content/knowledge/*.md file from the classpath, upserts the document, and (re-)ingests its chunks. */
    public int ingestAllFromClasspath() {
        int count = 0;
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources("classpath:knowledge/*.md");
            for (Resource resource : resources) {
                String raw = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                Matcher m = FRONTMATTER.matcher(raw);
                if (!m.matches()) {
                    log.warn("Skipping {} - no real frontmatter block found", resource.getFilename());
                    continue;
                }
                Map<String, String> frontmatter = parseFrontmatter(m.group(1));
                String body = m.group(2).strip();
                UUID id = documents.upsertByTitle(
                    frontmatter.getOrDefault("doc_type", "FAQ"),
                    frontmatter.getOrDefault("title", resource.getFilename()),
                    body,
                    frontmatter.get("occasion"),
                    frontmatter.getOrDefault("author", "unknown"));
                ingest(id);
                count++;
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read content/knowledge/*.md from the classpath", e);
        }
        log.info("Knowledge ingest complete: {} document(s)", count);
        return count;
    }

    private Map<String, String> parseFrontmatter(String block) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String line : block.split("\n")) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                result.put(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
            }
        }
        return result;
    }
}
