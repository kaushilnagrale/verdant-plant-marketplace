CREATE INDEX canonical_products_embedding_hnsw
    ON canonical_products
    USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);

CREATE TABLE mapping_decisions (
    id                   BIGSERIAL PRIMARY KEY,
    listing_id           UUID NOT NULL REFERENCES listings(id) ON DELETE CASCADE,
    canonical_product_id UUID REFERENCES canonical_products(id),
    confidence           REAL NOT NULL,
    decided_by           TEXT NOT NULL CHECK (decided_by IN ('AUTO','HUMAN')),
    actor_id             TEXT,
    decided_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ON mapping_decisions (listing_id, decided_at DESC);
