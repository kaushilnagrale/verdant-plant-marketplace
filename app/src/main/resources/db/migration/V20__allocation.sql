-- Not in the runbook - seller_serviceable_pincodes (Week 2) records where a
-- seller ships TO, but nothing recorded where they ship FROM, which the
-- allocation engine needs to look up transit_estimates(origin_zone, ...).
ALTER TABLE sellers ADD COLUMN origin_pincode TEXT CHECK (origin_pincode ~ '^[1-9][0-9]{5}$');

-- Not in the runbook either - the scoring formula needs a seller quality
-- signal and nothing recorded one. Defaults neutral (0.80); a real platform
-- would derive this from delivery/DOA history over time.
ALTER TABLE sellers ADD COLUMN quality_score NUMERIC(3,2) NOT NULL DEFAULT 0.80
    CHECK (quality_score >= 0 AND quality_score <= 1);

CREATE TABLE pincode_zones (
    pincode      TEXT PRIMARY KEY CHECK (pincode ~ '^[1-9][0-9]{5}$'),
    state        TEXT NOT NULL,
    zone         TEXT NOT NULL CHECK (zone IN ('LOCAL','REGIONAL','METRO','NATIONAL','REMOTE')),
    cod_eligible BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE transit_estimates (
    origin_zone      TEXT NOT NULL,
    destination_zone TEXT NOT NULL,
    transit_days     INT  NOT NULL CHECK (transit_days > 0),
    PRIMARY KEY (origin_zone, destination_zone)
);

CREATE TABLE allocations (
    id            UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    order_id      UUID NOT NULL,
    order_item_id UUID NOT NULL REFERENCES order_items(id),
    seller_id     UUID NOT NULL REFERENCES sellers(id),
    listing_id    UUID NOT NULL REFERENCES listings(id),
    transit_days  INT  NOT NULL,
    score         NUMERIC(8,5) NOT NULL,
    decided_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (order_item_id)
);

INSERT INTO transit_estimates (origin_zone, destination_zone, transit_days) VALUES
  ('LOCAL','LOCAL',1), ('LOCAL','REGIONAL',2), ('LOCAL','METRO',3),
  ('LOCAL','NATIONAL',5), ('LOCAL','REMOTE',7),
  ('REGIONAL','REGIONAL',2), ('REGIONAL','METRO',3), ('REGIONAL','NATIONAL',5),
  ('METRO','METRO',2), ('METRO','NATIONAL',4), ('METRO','REMOTE',7),
  ('NATIONAL','NATIONAL',4), ('NATIONAL','REMOTE',8),
  ('REMOTE','REMOTE',9);

-- The runbook's own rows are one-directional (e.g. LOCAL->METRO exists,
-- METRO->LOCAL doesn't) - a real courier network ships both ways between
-- two zones at roughly the same speed, and an asymmetric table would
-- silently exclude legitimate sellers shipping "backward" through the zone
-- hierarchy, not just score them worse. Mirrored explicitly rather than
-- assumed symmetric in code, since real transit times aren't always exactly
-- symmetric and this keeps that an explicit data decision.
INSERT INTO transit_estimates (origin_zone, destination_zone, transit_days) VALUES
  ('REGIONAL','LOCAL',2), ('METRO','LOCAL',3), ('NATIONAL','LOCAL',5), ('REMOTE','LOCAL',7),
  ('METRO','REGIONAL',3), ('NATIONAL','REGIONAL',5),
  ('NATIONAL','METRO',4), ('REMOTE','METRO',7),
  ('REMOTE','NATIONAL',8);
