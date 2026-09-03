-- Simulated/example data - this is a portfolio project with no real nursery
-- business behind it (user confirmed Week 6's "soft launch" is a documented
-- simulation, not a real go-live). Representative Indian pincodes/zones
-- standing in for what the runbook calls "your mother's real serviceable
-- pincode list."
INSERT INTO pincode_zones (pincode, state, zone, cod_eligible) VALUES
  ('411001','Maharashtra','LOCAL',TRUE),
  ('400001','Maharashtra','METRO',TRUE),
  ('560001','Karnataka','METRO',TRUE),
  ('110001','Delhi','NATIONAL',TRUE),
  ('797001','Nagaland','REMOTE',FALSE)
ON CONFLICT DO NOTHING;
