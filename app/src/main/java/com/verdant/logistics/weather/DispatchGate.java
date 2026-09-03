package com.verdant.logistics.weather;

import com.verdant.common.alerting.Alerting;
import com.verdant.common.flags.FeatureFlags;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * You cannot call a weather API per order at platform scale - by the time
 * this runs, ForecastRefreshJob has already populated weather_forecasts
 * for every pincode with pending dispatch, and this just reads it.
 */
@Service
public class DispatchGate {

    private static final Duration MAX_FORECAST_AGE = Duration.ofHours(12);
    private static final double HEAVY_RAIN_MM = 100.0;
    private static final int MAX_HOLD_DAYS = 5;

    private final JdbcTemplate jdbc;
    private final FeatureFlags flags;
    private final WeatherForecastRepository forecasts;
    private final DispatchHoldRepository holds;
    private final Alerting alerting;
    private final MeterRegistry meters;

    public DispatchGate(JdbcTemplate jdbc, FeatureFlags flags, WeatherForecastRepository forecasts,
                         DispatchHoldRepository holds, Alerting alerting, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.flags = flags;
        this.forecasts = forecasts;
        this.holds = holds;
        this.alerting = alerting;
        this.meters = meters;
    }

    public DispatchDecision evaluate(UUID shipmentId) {
        FeatureFlags.Flag flag = flags.get("weather_gating");
        if (!flag.enabled() && !flag.shadow()) {
            return DispatchDecision.proceed();
        }

        Map<String, Object> shipment = jdbc.queryForMap(
            "SELECT order_id, seller_id, destination_pincode, transit_days FROM shipments WHERE id = ?", shipmentId);
        String destinationPincode = (String) shipment.get("destination_pincode");
        int transitDays = shipment.get("transit_days") != null ? ((Number) shipment.get("transit_days")).intValue() : 4;

        Optional<ForecastWindow> window = forecasts.forWindow(
            destinationPincode, LocalDate.now(), LocalDate.now().plusDays(transitDays));

        // FAIL OPEN. A weather outage must never freeze the whole platform -
        // alert loudly and dispatch anyway, because a few risky shipments
        // is a far better outcome than every seller's dispatch silently
        // frozen by an API that happens to be down today.
        if (window.isEmpty() || window.get().isStalerThan(MAX_FORECAST_AGE)) {
            alerting.raise("Forecast unavailable or stale for " + destinationPincode + ", dispatching without gating");
            meters.counter("dispatch.gate.failed_open").increment();
            return DispatchDecision.proceed();
        }

        ForecastWindow f = window.get();
        HeatTolerance tolerance = lowestToleranceInParcel((UUID) shipment.get("order_id"), (UUID) shipment.get("seller_id"));

        if (f.maxTempC() > tolerance.maxSafeTempC()) {
            return decide(shipmentId, HoldReason.HEAT, f, flag);
        }
        if (f.totalRainfallMm() > HEAVY_RAIN_MM || f.hasAlert("FLOOD")) {
            return decide(shipmentId, HoldReason.RAIN, f, flag);
        }
        return DispatchDecision.proceed();
    }

    private DispatchDecision decide(UUID shipmentId, HoldReason reason, ForecastWindow f, FeatureFlags.Flag flag) {
        if (flag.shadow()) {
            holds.recordShadow(shipmentId, reason, f.snapshot()); // record, do not hold
            return DispatchDecision.proceed();
        }
        LocalDate maxHoldUntil = LocalDate.now().plusDays(MAX_HOLD_DAYS);
        holds.recordReal(shipmentId, reason, f.snapshot(), maxHoldUntil);
        return DispatchDecision.hold(reason, f.snapshot(), maxHoldUntil);
    }

    /** Lowest tolerance in the parcel wins - a box with a fern and a succulent is gated on the fern. */
    private HeatTolerance lowestToleranceInParcel(UUID orderId, UUID sellerId) {
        return jdbc.query("""
            SELECT cp.heat_tolerance
              FROM order_items oi
              JOIN listings l ON l.id = oi.listing_id
              JOIN canonical_products cp ON cp.id = l.canonical_product_id
             WHERE oi.order_id = ? AND oi.seller_id = ?
            """, (rs, rowNum) -> HeatTolerance.parse(rs.getString("heat_tolerance")), orderId, sellerId)
            .stream()
            .min(Comparator.comparingDouble(HeatTolerance::maxSafeTempC))
            .orElse(HeatTolerance.MEDIUM);
    }
}
