package com.verdant.logistics.weather;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

/** Aggregated forecast over a shipment's whole transit window, not a single day - a heat spike on day 3 of a 4-day transit still matters. */
public record ForecastWindow(double maxTempC, double totalRainfallMm, Set<String> alertTypes, Instant fetchedAt) {

    public boolean isStalerThan(Duration maxAge) {
        return Instant.now().isAfter(fetchedAt.plus(maxAge));
    }

    public boolean hasAlert(String type) {
        return alertTypes.contains(type);
    }

    /**
     * What actually gets stored in dispatch_holds.forecast_snapshot (a real
     * jsonb column) - the evidence for why a hold happened. Built with
     * org.json rather than Java's default Set.toString(), which produces
     * "[FLOOD]" (unquoted, not valid JSON) and was rejected by Postgres's
     * ::jsonb cast with a real DataIntegrityViolationException the first
     * time this actually ran against the real column, not assumed correct.
     */
    public String snapshot() {
        return new JSONObject()
            .put("maxTempC", maxTempC)
            .put("totalRainfallMm", totalRainfallMm)
            .put("alertTypes", new JSONArray(alertTypes))
            .put("fetchedAt", fetchedAt.toString())
            .toString();
    }
}
