-- Runbook calls this V20 - renumbered, same as every prior week.
CREATE TABLE payout_cycles (
    id            UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    seller_id     UUID NOT NULL REFERENCES sellers(id),
    period_start  DATE NOT NULL,
    period_end    DATE NOT NULL,
    status        TEXT NOT NULL CHECK (status IN
                    ('DRAFT','PENDING_APPROVAL','APPROVED','INITIATED','PAID','FAILED','CANCELLED')),
    gross_amount  NUMERIC(14,2) NOT NULL,
    withheld_amount NUMERIC(14,2) NOT NULL DEFAULT 0,
    net_amount    NUMERIC(14,2) NOT NULL,
    ledger_txn_id UUID REFERENCES ledger_transactions(id),
    provider_ref  TEXT,
    idempotency_key TEXT NOT NULL UNIQUE,
    approved_by   TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    paid_at       TIMESTAMPTZ,
    failure_reason TEXT,
    UNIQUE (seller_id, period_start, period_end)
);

CREATE TABLE payout_line_items (
    id             BIGSERIAL PRIMARY KEY,
    payout_cycle_id UUID NOT NULL REFERENCES payout_cycles(id) ON DELETE CASCADE,
    order_id       UUID NOT NULL,
    order_item_id  UUID NOT NULL,
    gross          NUMERIC(12,2) NOT NULL,
    commission     NUMERIC(12,2) NOT NULL,
    tcs            NUMERIC(12,2) NOT NULL,
    gateway_fee    NUMERIC(12,2) NOT NULL,
    claim_adjustment NUMERIC(12,2) NOT NULL DEFAULT 0,
    net            NUMERIC(12,2) NOT NULL
);

CREATE INDEX ON payout_cycles (seller_id, period_end DESC);
CREATE INDEX ON payout_cycles (status) WHERE status IN ('PENDING_APPROVAL','INITIATED');

-- Not in the runbook - checkout has always been 100% prepaid via Razorpay
-- (Week 4) and remains so; no order this platform actually creates is
-- ever COD. Added for real anyway, defaulting FALSE, because
-- PayoutEligibility's COD-vs-prepaid distinction is real production
-- knowledge worth encoding correctly even for a branch nothing currently
-- exercises - see PayoutEligibility's own doc comment.
ALTER TABLE orders ADD COLUMN is_cash_on_delivery BOOLEAN NOT NULL DEFAULT FALSE;

-- Also not in the runbook - "remittance" (the courier physically handing
-- over cash collected on delivery, days later) has no representation
-- anywhere in this schema. Minimal real table, not a stub: one row per
-- shipment once its COD cash has actually been remitted.
CREATE TABLE cod_remittances (
    shipment_id UUID PRIMARY KEY REFERENCES shipments(id),
    amount      NUMERIC(12,2) NOT NULL,
    remitted_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
