CREATE TABLE orders (
    id                   UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    buyer_email          TEXT,
    status               TEXT NOT NULL DEFAULT 'CREATED' CHECK (status IN
                           ('CREATED','PAID','EXPIRED','CANCELLED')),
    total_amount         NUMERIC(12,2) NOT NULL CHECK (total_amount >= 0),
    currency             TEXT NOT NULL DEFAULT 'INR',
    razorpay_order_id    TEXT UNIQUE,
    razorpay_payment_id  TEXT UNIQUE,
    expires_at           TIMESTAMPTZ NOT NULL,
    paid_at              TIMESTAMPTZ,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ON orders (expires_at) WHERE status = 'CREATED';

-- Closes the gap flagged when reservations were built (Day 21 equivalent):
-- a reservation that became a real sale must be permanently excluded from
-- the expiry sweep, distinct from one that was actively released back.
ALTER TABLE reservations ADD COLUMN fulfilled_at TIMESTAMPTZ;
