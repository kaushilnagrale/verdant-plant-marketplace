-- Not in the runbook by this shape - the runbook assumes a real
-- notifications system already exists ("Template: order_held_weather",
-- Day 41.2) and just queries it. No SMS/email provider is integrated in
-- this project (see LAUNCH_CHECKLIST.md) - this table is an honest record
-- of notification INTENT (what would have been sent, to whom, when),
-- not a claim that anything was actually delivered.
CREATE TABLE notifications (
    id          BIGSERIAL PRIMARY KEY,
    order_id    UUID,
    shipment_id UUID,
    event_type  TEXT NOT NULL,
    recipient   TEXT,
    status      TEXT NOT NULL DEFAULT 'RECORDED' CHECK (status IN ('RECORDED', 'SENT', 'FAILED')),
    body        TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ON notifications (shipment_id);
CREATE INDEX ON notifications (order_id);
