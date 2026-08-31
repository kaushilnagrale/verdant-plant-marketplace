CREATE TABLE listings (
    id                  UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    seller_id           UUID NOT NULL REFERENCES sellers(id),
    raw_title           TEXT NOT NULL,
    price_amount        NUMERIC(12,2) NOT NULL CHECK (price_amount >= 0),
    handling_time_hours INT NOT NULL DEFAULT 24,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE listings ENABLE ROW LEVEL SECURITY;
ALTER TABLE listings FORCE ROW LEVEL SECURITY;

CREATE POLICY listings_isolation ON listings
  USING (
    seller_id::text = current_setting('app.current_tenant', true)
    OR current_setting('app.is_platform_admin', true) = 'true'
  );

CREATE INDEX ON listings (seller_id);
