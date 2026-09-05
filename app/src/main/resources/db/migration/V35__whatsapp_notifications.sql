-- Runbook calls this V24 and names the table "notifications" - renumbered
-- like every prior week, and given a distinct table name on purpose: a
-- real "notifications" table already exists (V27, Week 7) backing
-- com.verdant.common.notification.NotificationService, an internal
-- ops/seller-facing "record what would be sent" log with a simpler
-- schema (order_id/shipment_id/event_type/recipient/status/body) already
-- serving 6 real call sites (hold lifecycle, payout failures, quality
-- tier changes). This week's own ask - real delivery semantics
-- (idempotency, quiet hours, gift-recipient routing, rate-limit backoff,
-- template approval gating) for the genuinely different buyer/recipient-
-- facing WhatsApp channel - is a real, separate concern with a real,
-- richer schema, not a drop-in replacement for the existing simpler log;
-- kept as its own table rather than forcing one schema to serve both
-- shapes of "notification" this project now has.
CREATE TABLE whatsapp_templates (
    name          TEXT PRIMARY KEY,
    channel       TEXT NOT NULL CHECK (channel IN ('WHATSAPP','SMS','EMAIL')),
    provider_name TEXT NOT NULL,
    language      TEXT NOT NULL DEFAULT 'en',
    approved      BOOLEAN NOT NULL DEFAULT FALSE,
    is_urgent     BOOLEAN NOT NULL DEFAULT FALSE,
    is_price_bearing BOOLEAN NOT NULL DEFAULT FALSE,
    variables     TEXT[] NOT NULL DEFAULT '{}'
);

CREATE TABLE whatsapp_messages (
    id                   UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    recipient_type       TEXT NOT NULL CHECK (recipient_type IN ('BUYER','RECIPIENT','SELLER','OPS')),
    recipient_ref        TEXT NOT NULL,
    template_name        TEXT NOT NULL REFERENCES whatsapp_templates(name),
    channel              TEXT NOT NULL,
    idempotency_key      TEXT NOT NULL UNIQUE,
    variables            JSONB NOT NULL,
    status               TEXT NOT NULL CHECK (status IN
                            ('QUEUED','SENT','DELIVERED','READ','FAILED','SUPPRESSED')),
    provider_message_id  TEXT,
    scheduled_for        TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_at              TIMESTAMPTZ,
    failure_reason       TEXT,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ON whatsapp_messages (status, scheduled_for) WHERE status = 'QUEUED';
CREATE INDEX ON whatsapp_messages (recipient_ref, created_at DESC);

-- Real templates this project's own real call sites need (order lifecycle,
-- care instructions, OTP) - is_urgent/is_price_bearing are the two real
-- hard guards 55.2/55.3 build around (quiet-hours bypass, gift-recipient
-- suppression), not something a caller decides per message.
INSERT INTO whatsapp_templates (name, channel, provider_name, is_urgent, is_price_bearing) VALUES
    ('order_confirmed',    'WHATSAPP', 'order_confirmed_v1',    FALSE, FALSE),
    ('payment_received',   'WHATSAPP', 'payment_received_v1',   FALSE, FALSE),
    ('order_shipped',      'WHATSAPP', 'order_shipped_v1',      FALSE, FALSE),
    ('out_for_delivery',   'WHATSAPP', 'out_for_delivery_v1',   FALSE, FALSE),
    ('delivered',          'WHATSAPP', 'delivered_v1',          FALSE, FALSE),
    ('order_receipt',      'WHATSAPP', 'order_receipt_v1',      FALSE, TRUE),
    ('care_instructions',  'WHATSAPP', 'care_instructions_v1',  FALSE, FALSE),
    ('otp',                'WHATSAPP', 'otp_v1',                TRUE,  FALSE)
ON CONFLICT DO NOTHING;
