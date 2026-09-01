CREATE TABLE inventory (
    listing_id UUID PRIMARY KEY REFERENCES listings(id) ON DELETE CASCADE,
    quantity   INT    NOT NULL CHECK (quantity >= 0),
    buffer     INT    NOT NULL DEFAULT 1 CHECK (buffer >= 0),
    version    BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE inventory_adjustments (
    id         BIGSERIAL PRIMARY KEY,
    listing_id UUID NOT NULL REFERENCES listings(id),
    delta      INT  NOT NULL,
    reason     TEXT NOT NULL CHECK (reason IN
                 ('RESTOCK','SALE','RESERVATION_RELEASE','MORTALITY','CORRECTION','OFFLINE_SALE')),
    actor_id   TEXT NOT NULL,
    at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE reservations (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    order_id    UUID NOT NULL,
    listing_id  UUID NOT NULL REFERENCES listings(id),
    quantity    INT  NOT NULL CHECK (quantity > 0),
    expires_at  TIMESTAMPTZ NOT NULL,
    released_at TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (order_id, listing_id)
);

CREATE INDEX ON reservations (expires_at) WHERE released_at IS NULL;
CREATE INDEX ON inventory_adjustments (listing_id, at DESC);

ALTER TABLE inventory ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory FORCE ROW LEVEL SECURITY;
CREATE POLICY inventory_isolation ON inventory
  USING (
    EXISTS (SELECT 1 FROM listings l
             WHERE l.id = inventory.listing_id
               AND l.seller_id::text = current_setting('app.current_tenant', true))
    OR current_setting('app.is_platform_admin', true) = 'true'
  );
