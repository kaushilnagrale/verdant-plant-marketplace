CREATE TABLE outbox (
    id              BIGSERIAL PRIMARY KEY,
    aggregate_type  TEXT NOT NULL,
    aggregate_id    UUID NOT NULL,
    event_type      TEXT NOT NULL,
    partition_key   TEXT NOT NULL,
    payload         JSONB NOT NULL,
    published_at    TIMESTAMPTZ,
    attempts        INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_error      TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- partial index stays small forever: only unpublished rows
CREATE INDEX outbox_pending ON outbox (next_attempt_at)
    WHERE published_at IS NULL;

CREATE INDEX ON outbox (aggregate_id, created_at);
