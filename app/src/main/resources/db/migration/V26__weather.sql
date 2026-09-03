-- Runbook calls this V18 - renumbered, same as every prior week.
CREATE TABLE pincode_coordinates (
    pincode   TEXT PRIMARY KEY REFERENCES pincode_zones(pincode),
    latitude  NUMERIC(9,6) NOT NULL,
    longitude NUMERIC(9,6) NOT NULL
);

CREATE TABLE weather_forecasts (
    pincode       TEXT NOT NULL,
    forecast_date DATE NOT NULL,
    max_temp_c    NUMERIC(4,1) NOT NULL,
    min_temp_c    NUMERIC(4,1) NOT NULL,
    rainfall_mm   NUMERIC(6,2) NOT NULL DEFAULT 0,
    alert_type    TEXT,
    fetched_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (pincode, forecast_date)
);

CREATE INDEX ON weather_forecasts (fetched_at);
