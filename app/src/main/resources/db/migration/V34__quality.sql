-- Runbook calls this V23 - renumbered, same as every prior week (V23 is
-- already the feature-flags migration, Week 7).
CREATE TABLE seller_quality_events (
    id          BIGSERIAL PRIMARY KEY,
    seller_id   UUID NOT NULL REFERENCES sellers(id),
    event_type  TEXT NOT NULL CHECK (event_type IN (
                  'DISPATCH_ON_TIME','DISPATCH_LATE','ORDER_CANCELLED_BY_SELLER',
                  'CLAIM_UPHELD','CLAIM_REJECTED','RTO_RECEIVED',
                  'OPS_RESPONSE_FAST','OPS_RESPONSE_SLOW','PHOTO_DISPUTE')),
    order_id    UUID,
    weight      REAL NOT NULL DEFAULT 1.0,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE seller_quality_scores (
    seller_id       UUID PRIMARY KEY REFERENCES sellers(id),
    score           REAL NOT NULL CHECK (score >= 0 AND score <= 1),
    components      JSONB NOT NULL,
    order_count     INT NOT NULL,
    is_provisional  BOOLEAN NOT NULL DEFAULT TRUE,
    tier            TEXT NOT NULL CHECK (tier IN ('PROBATION','STANDARD','PREFERRED','RESTRICTED')),
    computed_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    previous_score  REAL,
    previous_tier   TEXT
);

CREATE TABLE seller_quality_history (
    id         BIGSERIAL PRIMARY KEY,
    seller_id  UUID NOT NULL REFERENCES sellers(id),
    score      REAL NOT NULL,
    tier       TEXT NOT NULL,
    components JSONB NOT NULL,
    computed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ON seller_quality_events (seller_id, occurred_at DESC);
CREATE INDEX ON seller_quality_history (seller_id, computed_at DESC);
