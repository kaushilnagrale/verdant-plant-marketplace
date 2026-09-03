-- Runbook calls this V17 and has it CREATE TABLE shipments from scratch -
-- shipments already exists (V22, Week 6): one row per (order, seller),
-- wired into OrderSagaOrchestrator/SellerOrderController with a simpler
-- status set (BOOKED/PACKED/DISPATCHED/CANCELLED). ALTERing it rather than
-- recreating it - dropping and rebuilding would orphan the saga/seller-
-- dispatch code and lose the 2 real shipment rows already booked this
-- project. Existing rows predate real courier tracking, so the new columns
-- are nullable rather than backfilled with fake data.
ALTER TABLE shipments
    ADD COLUMN origin_pincode           TEXT,
    ADD COLUMN destination_pincode      TEXT,
    ADD COLUMN courier                  TEXT,
    ADD COLUMN awb_number               TEXT UNIQUE,
    ADD COLUMN label_url                TEXT,
    ADD COLUMN booking_idempotency_key  TEXT UNIQUE,
    ADD COLUMN dead_weight_grams        INT,
    ADD COLUMN volumetric_grams         INT,
    ADD COLUMN billable_grams           INT,
    ADD COLUMN shipping_amount          NUMERIC(12,2),
    ADD COLUMN transit_days             INT,
    ADD COLUMN planned_dispatch_date    DATE,
    ADD COLUMN delivered_at             TIMESTAMPTZ;

-- Widened, not replaced: BOOKED/PACKED/DISPATCHED/CANCELLED are the
-- existing seller-dispatch lifecycle (V22) and stay meaningful; the rest
-- are the real courier tracking lifecycle this week adds. PACKED sits
-- between BOOKED (the platform books with the courier) and PICKED_UP (the
-- courier's own tracking event) - the seller's own action, not the
-- courier's. DISPATCHED is the existing seller-side "handed off" status,
-- kept alongside the new PICKED_UP/IN_TRANSIT courier-tracking states until
-- real tracking data supersedes it.
--
-- First attempt at this constraint (verified against the real dev
-- database, not just applied blindly) dropped DISPATCHED by mistake -
-- Postgres refused with "violated by some row" against the 2 real
-- shipment rows already booked this project, caught before it ever
-- reached a shared environment.
ALTER TABLE shipments DROP CONSTRAINT shipments_status_check;
ALTER TABLE shipments ADD CONSTRAINT shipments_status_check CHECK (status IN
    ('PENDING','HELD','READY','BOOKED','PACKED','DISPATCHED','PICKED_UP','IN_TRANSIT',
     'OUT_FOR_DELIVERY','DELIVERED','FAILED','RTO','CANCELLED'));

CREATE TABLE shipment_events (
    id                BIGSERIAL PRIMARY KEY,
    shipment_id       UUID NOT NULL REFERENCES shipments(id),
    provider_event_id TEXT,
    status            TEXT NOT NULL,
    location          TEXT,
    remark            TEXT,
    occurred_at       TIMESTAMPTZ NOT NULL,
    received_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (shipment_id, provider_event_id)
);

CREATE TABLE dispatch_holds (
    id                UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    shipment_id       UUID NOT NULL REFERENCES shipments(id),
    reason            TEXT NOT NULL CHECK (reason IN
                        ('HEAT','RAIN','FLOOD','COURIER_DISRUPTION','SELLER_UNAVAILABLE','MANUAL')),
    forecast_snapshot JSONB,
    held_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    max_hold_until    TIMESTAMPTZ NOT NULL,
    released_at       TIMESTAMPTZ,
    released_by       TEXT,
    shadow            BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE INDEX ON shipments (status, planned_dispatch_date);
CREATE INDEX ON shipment_events (shipment_id, occurred_at DESC);
CREATE INDEX ON dispatch_holds (shipment_id) WHERE released_at IS NULL;
