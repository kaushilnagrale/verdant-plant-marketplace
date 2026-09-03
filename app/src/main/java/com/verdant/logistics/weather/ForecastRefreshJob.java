package com.verdant.logistics.weather;

import com.verdant.common.alerting.Alerting;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * A weather API call per order does not scale - this is a scheduled batch
 * over the pincodes that actually have pending dispatch (typically a few
 * dozen), not all of India. One failed pincode does not abort the run
 * (same quarantine discipline as batch catalog ingestion, Week 3) - a
 * transient failure for one destination must never block the whole
 * refresh, which every other pincode's dispatch decision depends on.
 */
@Component
public class ForecastRefreshJob {

    private static final Logger log = LoggerFactory.getLogger(ForecastRefreshJob.class);
    private static final int BATCH_SIZE = 50;

    private final JdbcTemplate jdbc;
    private final PincodeCoordinateRepository coordinates;
    private final WeatherForecastRepository forecasts;
    private final WeatherClient weatherClient;
    private final Alerting alerting;
    private final MeterRegistry meters;

    public ForecastRefreshJob(JdbcTemplate jdbc, PincodeCoordinateRepository coordinates,
                               WeatherForecastRepository forecasts, WeatherClient weatherClient,
                               Alerting alerting, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.coordinates = coordinates;
        this.forecasts = forecasts;
        this.weatherClient = weatherClient;
        this.alerting = alerting;
        this.meters = meters;
    }

    @Scheduled(cron = "${verdant.weather.refresh-cron:0 0 */6 * * *}")
    public void refresh() {
        List<String> active = pincodesWithPendingDispatch();

        for (int start = 0; start < active.size(); start += BATCH_SIZE) {
            List<String> batch = active.subList(start, Math.min(start + BATCH_SIZE, active.size()));
            for (String pincode : batch) {
                try {
                    var coords = coordinates.forPincode(pincode)
                        .orElseThrow(() -> new IllegalStateException("No coordinates for pincode " + pincode));
                    forecasts.upsertAll(pincode, weatherClient.sevenDayForecast(coords));
                } catch (Exception e) {
                    log.warn("Forecast fetch failed for {}", pincode, e);
                    meters.counter("weather.fetch.failed").increment();
                }
            }
            sleepBriefly();
        }

        long stale = forecasts.countStalerThan(Duration.ofHours(12));
        if (stale > 0) {
            alerting.raise(stale + " pincodes have stale forecast data");
        }
    }

    /** Not all of India - only destinations with a shipment that hasn't shipped yet. */
    private List<String> pincodesWithPendingDispatch() {
        return jdbc.queryForList("""
            SELECT DISTINCT destination_pincode FROM shipments
             WHERE destination_pincode IS NOT NULL
               AND status IN ('PENDING','HELD','READY','BOOKED','PACKED')
            """, String.class);
    }

    private void sleepBriefly() {
        try {
            Thread.sleep(200); // respect the provider's rate limit
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
