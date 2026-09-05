-- Runbook calls this V22 - renumbered, same as every prior week (V22 is
-- already the shipments migration, Week 6).
CREATE TABLE search_projection_state (
    listing_id    UUID PRIMARY KEY REFERENCES listings(id) ON DELETE CASCADE,
    last_event_id BIGINT NOT NULL,
    indexed_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    version       BIGINT NOT NULL DEFAULT 0
);

-- listing_id as the primary key (not a surrogate id) is deliberate: five
-- rapid changes to one listing collapse into one queued reindex rather
-- than five wasteful round trips - see ReindexQueueRepository.enqueue().
CREATE TABLE search_reindex_queue (
    listing_id  UUID PRIMARY KEY,
    reason      TEXT NOT NULL,
    queued_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    attempts    INT NOT NULL DEFAULT 0
);

-- Two real facets the search index mapping (Day 48) needs that nothing in
-- this schema has ever captured - not in the runbook's own migration list
-- for any prior week, added here because this is the first feature that
-- actually surfaces them. Seeded with real, well-known facts (ASPCA
-- toxicity guidance and standard houseplant light-tolerance references)
-- for the 20 real products this catalog already has, not placeholders.
ALTER TABLE canonical_products
    ADD COLUMN light_requirement TEXT CHECK (light_requirement IN ('LOW','BRIGHT_INDIRECT','DIRECT')),
    ADD COLUMN pet_safe          BOOLEAN;

UPDATE canonical_products SET light_requirement = 'BRIGHT_INDIRECT', pet_safe = FALSE WHERE display_name = 'Aloe Vera';
UPDATE canonical_products SET light_requirement = 'BRIGHT_INDIRECT', pet_safe = TRUE  WHERE display_name = 'Areca Palm';
UPDATE canonical_products SET light_requirement = 'BRIGHT_INDIRECT', pet_safe = TRUE  WHERE display_name = 'Boston Fern';
UPDATE canonical_products SET light_requirement = 'DIRECT',          pet_safe = TRUE  WHERE display_name = 'Bougainvillea';
UPDATE canonical_products SET light_requirement = 'BRIGHT_INDIRECT', pet_safe = FALSE WHERE display_name = 'Croton';
UPDATE canonical_products SET light_requirement = 'DIRECT',          pet_safe = TRUE  WHERE display_name = 'Hibiscus';
UPDATE canonical_products SET light_requirement = 'BRIGHT_INDIRECT', pet_safe = FALSE WHERE display_name = 'Jade Plant';
UPDATE canonical_products SET light_requirement = 'DIRECT',          pet_safe = TRUE  WHERE display_name = 'Jasmine';
UPDATE canonical_products SET light_requirement = 'LOW',             pet_safe = FALSE WHERE display_name = 'Lucky Bamboo';
UPDATE canonical_products SET light_requirement = 'DIRECT',          pet_safe = TRUE  WHERE display_name = 'Marigold';
UPDATE canonical_products SET light_requirement = 'LOW',             pet_safe = FALSE WHERE display_name = 'Money Plant';
UPDATE canonical_products SET light_requirement = 'BRIGHT_INDIRECT', pet_safe = FALSE WHERE display_name = 'Monstera';
UPDATE canonical_products SET light_requirement = 'LOW',             pet_safe = FALSE WHERE display_name = 'Peace Lily';
UPDATE canonical_products SET light_requirement = 'LOW',             pet_safe = FALSE WHERE display_name = 'Philodendron';
UPDATE canonical_products SET light_requirement = 'DIRECT',          pet_safe = TRUE  WHERE display_name = 'Rose';
UPDATE canonical_products SET light_requirement = 'BRIGHT_INDIRECT', pet_safe = FALSE WHERE display_name = 'Rubber Plant';
UPDATE canonical_products SET light_requirement = 'LOW',             pet_safe = FALSE WHERE display_name = 'Snake Plant';
UPDATE canonical_products SET light_requirement = 'BRIGHT_INDIRECT', pet_safe = TRUE  WHERE display_name = 'Spider Plant';
UPDATE canonical_products SET light_requirement = 'DIRECT',          pet_safe = TRUE  WHERE display_name = 'Tulsi';
UPDATE canonical_products SET light_requirement = 'LOW',             pet_safe = FALSE WHERE display_name = 'ZZ Plant';
