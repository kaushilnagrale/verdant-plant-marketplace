ALTER TABLE sellers
    ADD COLUMN tax_declaration_complete BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN gstin                    TEXT,
    ADD COLUMN gst_registered           BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN handling_time_hours      INT NOT NULL DEFAULT 24,
    ADD COLUMN updated_at               TIMESTAMPTZ NOT NULL DEFAULT now();

CREATE TABLE seller_status_history (
    id         BIGSERIAL PRIMARY KEY,
    seller_id  UUID NOT NULL REFERENCES sellers(id),
    from_status TEXT,
    to_status   TEXT NOT NULL,
    actor_id    TEXT NOT NULL,
    reason      TEXT,
    at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE bank_verifications (
    id             UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    seller_id      UUID NOT NULL REFERENCES sellers(id),
    account_last4  TEXT NOT NULL,
    ifsc           TEXT NOT NULL,
    reference_code TEXT NOT NULL,
    status         TEXT NOT NULL CHECK (status IN ('INITIATED','VERIFIED','FAILED')),
    attempts       INT NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE seller_serviceable_pincodes (
    seller_id UUID NOT NULL REFERENCES sellers(id),
    pincode   TEXT NOT NULL CHECK (pincode ~ '^[1-9][0-9]{5}$'),
    PRIMARY KEY (seller_id, pincode)
);

CREATE INDEX ON seller_status_history (seller_id, at DESC);
