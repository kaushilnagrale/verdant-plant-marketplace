-- Not in the runbook - a real gap found while starting Week 6's allocation
-- engine: orders (V12) only ever stored an aggregate total, never which
-- listings/sellers/prices actually made up the order. The allocation engine
-- needs to route each individual line to a seller, so that breakdown has to
-- be durable, not just transient CartQuote data discarded after checkout.
CREATE TABLE order_items (
    id         UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    order_id   UUID NOT NULL REFERENCES orders(id),
    listing_id UUID NOT NULL REFERENCES listings(id),
    seller_id  UUID NOT NULL REFERENCES sellers(id),
    quantity   INT NOT NULL CHECK (quantity > 0),
    unit_price NUMERIC(12,2) NOT NULL CHECK (unit_price >= 0),
    line_total NUMERIC(12,2) NOT NULL CHECK (line_total >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ON order_items (order_id);
CREATE INDEX ON order_items (seller_id);
