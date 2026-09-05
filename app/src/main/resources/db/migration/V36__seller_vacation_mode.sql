-- Not in the runbook's own Day 53-55 migrations - real, missing
-- infrastructure the Day 56 seller app assumes already exists ("a
-- vacation mode switch that pauses new orders. Essential for a one
-- person operation") but nowhere in this project's prior 10 weeks ever
-- built. Added here rather than left as a frontend-only toggle with
-- nothing real behind it.
ALTER TABLE sellers ADD COLUMN on_vacation BOOLEAN NOT NULL DEFAULT FALSE;
