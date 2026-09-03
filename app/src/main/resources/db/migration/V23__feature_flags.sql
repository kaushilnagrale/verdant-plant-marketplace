-- Runbook calls this V16 - already taken (ledger_balance_constraint). Renumbered,
-- same pattern as every prior week's migration collision.
CREATE TABLE feature_flags (
    name        TEXT PRIMARY KEY,
    enabled     BOOLEAN NOT NULL DEFAULT FALSE,
    shadow_mode BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by  TEXT
);

INSERT INTO feature_flags (name, enabled, shadow_mode) VALUES
  ('weather_gating',     FALSE, TRUE),
  ('shiprocket_booking', FALSE, FALSE),
  ('auto_dispatch_hold', FALSE, FALSE);
