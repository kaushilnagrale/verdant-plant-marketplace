-- Governs whether a freshly-built payout cycle can be auto-approved and
-- auto-initiated, versus always waiting for a human (Day 47.1). Starts
-- FALSE and stays that way for real: every cycle in this project so far
-- is approved by hand through PayoutAdminController, and nothing turns
-- this flag on automatically - it exists to be flipped deliberately, once
-- there's a genuine track record of clean reconciliation to justify it.
INSERT INTO feature_flags (name, enabled, shadow_mode) VALUES
  ('auto_payout', FALSE, FALSE)
ON CONFLICT (name) DO NOTHING;
