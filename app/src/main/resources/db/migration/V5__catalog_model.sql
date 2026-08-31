CREATE TABLE canonical_products (
    id             UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    botanical_name TEXT NOT NULL,
    display_name   TEXT NOT NULL,
    common_names   TEXT[] NOT NULL DEFAULT '{}',
    size_tier      TEXT NOT NULL CHECK (size_tier IN ('SMALL','MEDIUM','LARGE','SPECIMEN')),
    hsn_code       TEXT NOT NULL DEFAULT '0602',
    heat_tolerance TEXT NOT NULL CHECK (heat_tolerance IN ('LOW','MEDIUM','HIGH')),
    max_transit_days INT NOT NULL DEFAULT 4,
    care_profile   JSONB NOT NULL DEFAULT '{}',
    embedding      vector(768),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (botanical_name, size_tier)
);

ALTER TABLE listings
    ADD COLUMN canonical_product_id UUID REFERENCES canonical_products(id),
    ADD COLUMN mapping_confidence   REAL,
    ADD COLUMN mapping_status       TEXT NOT NULL DEFAULT 'UNMAPPED'
        CHECK (mapping_status IN ('UNMAPPED','AUTO_MAPPED','PENDING_REVIEW','MANUAL')),
    ADD COLUMN description          TEXT,
    ADD COLUMN active               BOOLEAN NOT NULL DEFAULT TRUE;

CREATE TABLE listing_images (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    listing_id  UUID NOT NULL REFERENCES listings(id) ON DELETE CASCADE,
    s3_key      TEXT NOT NULL,
    position    INT NOT NULL DEFAULT 0,
    width       INT,
    height      INT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE listing_images ENABLE ROW LEVEL SECURITY;
ALTER TABLE listing_images FORCE ROW LEVEL SECURITY;

CREATE POLICY listing_images_isolation ON listing_images
  USING (
    EXISTS (SELECT 1 FROM listings l
             WHERE l.id = listing_images.listing_id
               AND l.seller_id::text = current_setting('app.current_tenant', true))
    OR current_setting('app.is_platform_admin', true) = 'true'
  );

CREATE INDEX ON listings (canonical_product_id);
CREATE INDEX ON listings (mapping_status) WHERE mapping_status = 'PENDING_REVIEW';
CREATE INDEX ON listing_images (listing_id, position);

CREATE FUNCTION immutable_product_search_text(display_name text, botanical_name text, common_names text[])
RETURNS tsvector AS $$
  SELECT to_tsvector('simple',
    display_name || ' ' || botanical_name || ' ' || array_to_string(common_names, ' '));
$$ LANGUAGE sql IMMUTABLE;

CREATE INDEX canonical_products_fts ON canonical_products
  USING GIN (immutable_product_search_text(display_name, botanical_name, common_names));
