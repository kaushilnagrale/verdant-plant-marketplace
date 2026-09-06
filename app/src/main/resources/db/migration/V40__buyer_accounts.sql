-- Real buyer accounts: until now there was no buyer identity model at
-- all (CheckoutController's own doc comment used to say so explicitly)
-- - checkout only ever collected a free-text email and a delivery
-- pincode, and there was nowhere to look an order up again afterward.
-- Same real pattern as seller_users: a local row keyed by the buyer's
-- real Keycloak subject id, not a locally-managed password.
CREATE TABLE buyers (
    id         UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    subject_id TEXT NOT NULL UNIQUE,
    email      TEXT NOT NULL UNIQUE,
    name       TEXT NOT NULL,
    phone      TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Nullable, not NOT NULL - same reasoning as destination_pincode (V24):
-- orders placed before this migration have nothing honest to backfill
-- these with. Every new order via checkout() populates all of them
-- (enforced at the API layer, matching destination_pincode's own
-- precedent exactly), now that checkout requires a logged-in buyer.
ALTER TABLE orders
    ADD COLUMN buyer_id        UUID REFERENCES buyers(id),
    ADD COLUMN recipient_name  TEXT,
    ADD COLUMN recipient_phone TEXT,
    ADD COLUMN address_line1   TEXT,
    ADD COLUMN address_line2   TEXT,
    ADD COLUMN city            TEXT,
    ADD COLUMN state           TEXT;

CREATE INDEX ON orders (buyer_id, created_at DESC);
