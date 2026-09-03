CREATE TABLE rate_config (
    id             BIGSERIAL PRIMARY KEY,
    rate_type      TEXT NOT NULL CHECK (rate_type IN ('COMMISSION','TCS','TDS','GATEWAY_FEE')),
    scope_ref      UUID,                          -- NULL = platform default
    rate           NUMERIC(6,4) NOT NULL CHECK (rate >= 0 AND rate <= 1),
    effective_from DATE NOT NULL,
    effective_to   DATE,
    CHECK (effective_to IS NULL OR effective_to > effective_from)
);

CREATE INDEX ON rate_config (rate_type, scope_ref, effective_from DESC);

-- Placeholder values. VERIFY WITH A CHARTERED ACCOUNTANT before real payouts.
INSERT INTO rate_config (rate_type, rate, effective_from) VALUES
  ('COMMISSION',  0.1200, '2020-01-01'),
  ('TCS',         0.0100, '2020-01-01'),
  ('TDS',         0.0100, '2020-01-01'),
  ('GATEWAY_FEE', 0.0200, '2020-01-01');
