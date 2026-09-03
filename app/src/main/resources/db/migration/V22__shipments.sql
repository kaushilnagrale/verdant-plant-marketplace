-- Not in the runbook. StubLogisticsGateway (Day 33) only tracked bookings
-- in memory, one per ORDER - but an order can span multiple sellers
-- (order_items, Day 32), and each seller needs to independently mark THEIR
-- OWN portion packed/dispatched. Real, persisted, one row per (order,
-- seller), matching the same per-seller pattern SettlementService already
-- established.
CREATE TABLE shipments (
    id            UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    order_id      UUID NOT NULL REFERENCES orders(id),
    seller_id     UUID NOT NULL REFERENCES sellers(id),
    status        TEXT NOT NULL DEFAULT 'BOOKED' CHECK (status IN ('BOOKED','PACKED','DISPATCHED','CANCELLED')),
    reference     TEXT,
    packed_at     TIMESTAMPTZ,
    dispatched_at TIMESTAMPTZ,
    cancelled_at  TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (order_id, seller_id)
);

CREATE INDEX ON shipments (seller_id, status);
