CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE sellers (
    id            UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    business_name TEXT NOT NULL,
    status        TEXT NOT NULL CHECK (status IN
                    ('DRAFT','PENDING_REVIEW','APPROVED','SUSPENDED','OFFBOARDED')),
    bank_verified BOOLEAN NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE seller_users (
    id         UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    seller_id  UUID NOT NULL REFERENCES sellers(id),
    subject_id TEXT NOT NULL UNIQUE,
    role       TEXT NOT NULL CHECK (role IN ('OWNER','STAFF'))
);

CREATE TABLE audit_log (
    id        BIGSERIAL PRIMARY KEY,
    actor_id  TEXT NOT NULL,
    action    TEXT NOT NULL,
    resource  TEXT NOT NULL,
    seller_id UUID,
    at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ON seller_users (seller_id);
CREATE INDEX ON audit_log (seller_id, at DESC);
