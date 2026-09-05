-- Runbook calls this V25 - renumbered, same as every prior week (V25 is
-- already the logistics migration, Week 7). vector(768) matches this
-- project's own real, already-working embedding model exactly (Week 3's
-- HybridRetriever/EmbeddingModel bean, already producing 768-dim vectors
-- for canonical_products.embedding, V5) - reused here rather than
-- standing up a second embedding pipeline.
CREATE TABLE knowledge_documents (
    id            UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    doc_type      TEXT NOT NULL CHECK (doc_type IN
                    ('CARE_GUIDE','SYMPTOM_GUIDE','OCCASION_GUIDE','CULTURAL_NOTE','FAQ')),
    title         TEXT NOT NULL,
    body          TEXT NOT NULL,
    canonical_product_id UUID REFERENCES canonical_products(id),
    occasion      TEXT,
    language      TEXT NOT NULL DEFAULT 'en',
    author        TEXT NOT NULL,
    reviewed_by   TEXT,
    reviewed_at   TIMESTAMPTZ,
    active        BOOLEAN NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE knowledge_chunks (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    document_id UUID NOT NULL REFERENCES knowledge_documents(id) ON DELETE CASCADE,
    chunk_index INT NOT NULL,
    heading     TEXT,
    text        TEXT NOT NULL,
    embedding   vector(768),
    UNIQUE (document_id, chunk_index)
);

CREATE INDEX knowledge_chunks_hnsw ON knowledge_chunks
    USING hnsw (embedding vector_cosine_ops) WITH (m = 16, ef_construction = 64);

CREATE INDEX knowledge_chunks_fts ON knowledge_chunks
    USING GIN (to_tsvector('simple', coalesce(heading,'') || ' ' || text));

CREATE TABLE assistant_interactions (
    id             UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    feature        TEXT NOT NULL CHECK (feature IN ('GIFT_CONCIERGE','PLANT_DOCTOR')),
    user_subject_id TEXT,
    question       TEXT NOT NULL,
    outcome        TEXT NOT NULL CHECK (outcome IN
                     ('ANSWERED','REFUSED_NO_RETRIEVAL','REFUSED_OUT_OF_SCOPE','ESCALATED','ERROR')),
    cited_chunk_ids UUID[],
    top_similarity REAL,
    latency_ms     INT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ON assistant_interactions (feature, created_at DESC);
CREATE INDEX ON assistant_interactions (outcome);
