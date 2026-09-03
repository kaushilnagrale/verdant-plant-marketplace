package com.verdant.logistics.weather;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
public class WeatherForecastRepository {

    private final JdbcTemplate jdbc;

    public WeatherForecastRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void upsertAll(String pincode, List<WeatherClient.DailyForecast> forecasts) {
        for (var f : forecasts) {
            jdbc.update("""
                INSERT INTO weather_forecasts (pincode, forecast_date, max_temp_c, min_temp_c, rainfall_mm, alert_type, fetched_at)
                VALUES (?, ?, ?, ?, ?, ?, now())
                ON CONFLICT (pincode, forecast_date)
                DO UPDATE SET max_temp_c = EXCLUDED.max_temp_c, min_temp_c = EXCLUDED.min_temp_c,
                              rainfall_mm = EXCLUDED.rainfall_mm, alert_type = EXCLUDED.alert_type,
                              fetched_at = now()
                """, pincode, f.date(), f.maxTempC(), f.minTempC(), f.rainfallMm(), f.alertType());
        }
    }

    /** Aggregated across the whole window - a heat spike on day 3 of a 4-day transit still matters, not just day 1. */
    public Optional<ForecastWindow> forWindow(String pincode, LocalDate from, LocalDate to) {
        List<java.util.Map<String, Object>> rows = jdbc.queryForList("""
            SELECT max_temp_c, rainfall_mm, alert_type, fetched_at
              FROM weather_forecasts
             WHERE pincode = ? AND forecast_date BETWEEN ? AND ?
            """, pincode, from, to);

        if (rows.isEmpty()) {
            return Optional.empty();
        }

        double maxTemp = rows.stream().mapToDouble(r -> ((Number) r.get("max_temp_c")).doubleValue()).max().orElse(0);
        double totalRain = rows.stream().mapToDouble(r -> ((Number) r.get("rainfall_mm")).doubleValue()).sum();
        Set<String> alerts = new HashSet<>();
        Instant oldestFetch = Instant.now();
        for (var r : rows) {
            String alert = (String) r.get("alert_type");
            if (alert != null) alerts.add(alert);
            Instant fetchedAt = ((Timestamp) r.get("fetched_at")).toInstant();
            if (fetchedAt.isBefore(oldestFetch)) oldestFetch = fetchedAt;
        }

        return Optional.of(new ForecastWindow(maxTemp, totalRain, alerts, oldestFetch));
    }

    public long countStalerThan(Duration age) {
        return jdbc.queryForObject(
            "SELECT count(DISTINCT pincode) FROM weather_forecasts WHERE fetched_at < ?", Long.class,
            Timestamp.from(Instant.now().minus(age)));
    }
}
