-- Runbook calls this V21 - renumbered, same as every prior week (V21 is
-- already the order saga migration from Week 6).
CREATE TABLE reconciliation_runs (
    id             UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    run_date       DATE NOT NULL UNIQUE,
    provider_count INT NOT NULL DEFAULT 0,
    internal_count INT NOT NULL DEFAULT 0,
    matched        INT NOT NULL DEFAULT 0,
    breaks_found   INT NOT NULL DEFAULT 0,
    status         TEXT NOT NULL CHECK (status IN ('RUNNING','CLEAN','BREAKS','FAILED')),
    started_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at   TIMESTAMPTZ
);

CREATE TABLE reconciliation_breaks (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    run_id          UUID NOT NULL REFERENCES reconciliation_runs(id),
    break_type      TEXT NOT NULL CHECK (break_type IN
                      ('MISSING_INTERNAL','MISSING_PROVIDER','AMOUNT_MISMATCH','STATUS_MISMATCH')),
    provider_ref    TEXT,
    order_id        UUID,
    provider_amount NUMERIC(12,2),
    internal_amount NUMERIC(12,2),
    resolved_at     TIMESTAMPTZ,
    resolution_note TEXT
);

CREATE INDEX ON reconciliation_breaks (run_id) WHERE resolved_at IS NULL;
