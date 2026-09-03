-- Postgres never auto-indexes a foreign key column (only the side it
-- references) - reverses_id had none, discovered when a delete against a
-- large ledger_transactions table (Day 30's load test) took Postgres's
-- internal FK check ("does anything reference this row via reverses_id?")
-- from an indexed lookup to a full sequential scan, once per deleted row.
CREATE INDEX ON ledger_transactions (reverses_id);
