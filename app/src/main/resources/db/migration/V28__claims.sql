-- Runbook calls this V19 - renumbered, same as every prior week.
CREATE TABLE claims (
    id                UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    order_item_id     UUID NOT NULL REFERENCES order_items(id),
    shipment_id       UUID NOT NULL REFERENCES shipments(id),
    buyer_subject_id  TEXT NOT NULL,
    type              TEXT NOT NULL CHECK (type IN ('DEAD_ON_ARRIVAL','DAMAGED','WRONG_ITEM','NOT_DELIVERED')),
    status            TEXT NOT NULL CHECK (status IN
                        ('SUBMITTED','UNDER_REVIEW','APPROVED','REJECTED','RESOLVED')),
    fault             TEXT CHECK (fault IN ('SELLER','COURIER','PLATFORM','BUYER','UNDETERMINED')),
    fault_confidence  REAL,
    fault_signals     JSONB,
    resolution        TEXT CHECK (resolution IN ('REPLACEMENT','STORE_CREDIT','REFUND','REJECTED')),
    resolution_amount NUMERIC(12,2),
    ledger_txn_id     UUID REFERENCES ledger_transactions(id),
    submitted_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at       TIMESTAMPTZ,
    resolved_by       TEXT,
    UNIQUE (order_item_id)                      -- one claim per item
);

CREATE TABLE claim_photos (
    id         UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    claim_id   UUID NOT NULL REFERENCES claims(id) ON DELETE CASCADE,
    s3_key     TEXT NOT NULL,
    uploaded_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE claim_windows (
    order_item_id UUID PRIMARY KEY REFERENCES order_items(id),
    opens_at      TIMESTAMPTZ NOT NULL,
    closes_at     TIMESTAMPTZ NOT NULL,
    CHECK (closes_at > opens_at)
);

CREATE INDEX ON claims (status) WHERE status IN ('SUBMITTED','UNDER_REVIEW');
CREATE INDEX ON claim_windows (closes_at);
