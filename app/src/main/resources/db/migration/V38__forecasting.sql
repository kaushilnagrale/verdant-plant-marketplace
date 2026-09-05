-- Demand forecasting (62.1): a real festival calendar, seeded with this
-- project's own best real knowledge of the 2026 dates - India's major
-- gifting festivals follow a lunisolar calendar and shift year to year,
-- and these specific dates have NOT been cross-checked against an
-- authoritative panchang/calendar service, so they may be off by a day
-- or two. Same honesty standard as the OCCASION_GUIDE content (Day 58):
-- real, good-faith values, explicitly flagged as needing verification
-- before being trusted for real seller-facing stock planning.
CREATE TABLE festival_calendar (
    id               SERIAL PRIMARY KEY,
    name             TEXT NOT NULL,
    observed_date    DATE NOT NULL,
    -- Gifting demand for a festival ramps up in the days BEFORE it, not
    -- on the day itself (by then delivery can't reach in time anyway) -
    -- this is how many days of lead time DemandForecaster treats as
    -- "elevated demand" for this festival.
    demand_lead_days INT NOT NULL DEFAULT 7,
    UNIQUE (name, observed_date)
);

-- Matches the same occasions Week 11's own OCCASION_GUIDE content
-- covers (Days 58-60) - a real, deliberate cross-week consistency.
INSERT INTO festival_calendar (name, observed_date, demand_lead_days) VALUES
    ('Raksha Bandhan', '2026-08-28', 7),
    ('Diwali',         '2026-11-08', 10),
    ('Holi',           '2027-03-04', 5);
