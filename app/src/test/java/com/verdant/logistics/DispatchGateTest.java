package com.verdant.logistics;

import com.verdant.common.alerting.Alerting;
import com.verdant.common.testing.AllocationFixtures;
import com.verdant.common.testing.IntegrationTestBase;
import com.verdant.logistics.weather.DispatchGate;
import com.verdant.logistics.weather.HoldReason;
import com.verdant.logistics.weather.DispatchHoldRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Builds shipment fixtures directly rather than through a real checkout -
 * DispatchGate's own logic is what's under test here (already proven that
 * real orders reach a real BOOKED shipment elsewhere, ShipmentBookingTest).
 * This also sidesteps a real constraint: a full checkout requires a
 * pincode_zones row for the destination (AllocationEngine needs a zone to
 * score against), but missingForecast_failsOpen_neverClosed deliberately
 * uses a destination with NO zone/coordinates data at all - impossible to
 * reach via a real checkout, exactly the scenario the test needs.
 */
class DispatchGateTest extends IntegrationTestBase {

    @Autowired private DispatchGate gate;
    @Autowired private DispatchHoldRepository holdRepo;
    @Autowired private Alerting alerts;
    @Autowired private CacheManager cacheManager;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        alerts.clear();
        AllocationFixtures.seedLocalPincodeZone(jdbc);
        setFlag("weather_gating", true, false); // enabled, not shadow - the gate actually holds
    }

    private void setFlag(String name, boolean enabled, boolean shadow) {
        jdbc.update("UPDATE feature_flags SET enabled = ?, shadow_mode = ? WHERE name = ?", enabled, shadow, name);
        var cache = cacheManager.getCache("flags");
        if (cache != null) cache.evict(name);
    }

    private UUID shipmentWith(String heatTolerance, String destinationPincode) {
        return shipmentWithContents(destinationPincode, heatTolerance);
    }

    /** One shipment, one item per given tolerance - "mixed contents" tests pass more than one. */
    private UUID shipmentWithContents(String destinationPincode, String... tolerances) {
        UUID orderId = UUID.randomUUID();
        UUID sellerId = AllocationFixtures.seedApprovedSeller(jdbc, "Gate Test Nursery " + UUID.randomUUID());

        jdbc.update("""
            INSERT INTO orders (id, buyer_email, status, total_amount, currency, expires_at, destination_pincode)
            VALUES (?, 'gate-test@example.com', 'PAID', 199.00, 'INR', now() + interval '15 minutes', ?)
            """, orderId, destinationPincode);

        for (String tolerance : tolerances) {
            UUID canonicalProductId = AllocationFixtures.seedCanonicalProduct(jdbc, "Gate Test Plant (" + tolerance + ")");
            jdbc.update("UPDATE canonical_products SET heat_tolerance = ? WHERE id = ?", tolerance, canonicalProductId);
            UUID listingId = AllocationFixtures.seedListing(jdbc, sellerId, canonicalProductId, "Gate Test Plant", "199.00", 5);
            jdbc.update("""
                INSERT INTO order_items (order_id, listing_id, seller_id, quantity, unit_price, line_total)
                VALUES (?, ?, ?, 1, 199.00, 199.00)
                """, orderId, listingId, sellerId);
        }

        return jdbc.queryForObject("""
            INSERT INTO shipments (order_id, seller_id, status, origin_pincode, destination_pincode, transit_days)
            VALUES (?, ?, 'BOOKED', '411001', ?, 4) RETURNING id
            """, UUID.class, orderId, sellerId, destinationPincode);
    }

    private void seedForecast(String pincode, double maxTempC, double rainfallMm) {
        seedForecast(pincode, maxTempC, rainfallMm, Instant.now());
    }

    private void seedForecast(String pincode, double maxTempC, double rainfallMm, Instant fetchedAt) {
        jdbc.update("""
            INSERT INTO weather_forecasts (pincode, forecast_date, max_temp_c, min_temp_c, rainfall_mm, fetched_at)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (pincode, forecast_date) DO UPDATE SET max_temp_c = EXCLUDED.max_temp_c,
                rainfall_mm = EXCLUDED.rainfall_mm, fetched_at = EXCLUDED.fetched_at
            """, pincode, LocalDate.now(), maxTempC, maxTempC - 8, rainfallMm, Timestamp.from(fetchedAt));
    }

    private void seedForecastWithAlert(String pincode, double maxTempC, double rainfallMm, String alertType) {
        jdbc.update("""
            INSERT INTO weather_forecasts (pincode, forecast_date, max_temp_c, min_temp_c, rainfall_mm, alert_type, fetched_at)
            VALUES (?, ?, ?, ?, ?, ?, now())
            ON CONFLICT (pincode, forecast_date) DO UPDATE SET max_temp_c = EXCLUDED.max_temp_c,
                rainfall_mm = EXCLUDED.rainfall_mm, alert_type = EXCLUDED.alert_type, fetched_at = now()
            """, pincode, LocalDate.now(), maxTempC, maxTempC - 8, rainfallMm, alertType);
    }

    private Instant hoursAgo(int n) {
        return Instant.now().minusSeconds(n * 3600L);
    }

    @ParameterizedTest
    @CsvSource({
        "LOW,  33.0, false",    // fern, below its limit
        "LOW,  35.0, true",
        "HIGH, 40.0, false",    // succulent tolerates it
        "HIGH, 44.0, true"
    })
    void heatThreshold_isPerPlantCategory(String tolerance, double maxTemp, boolean shouldHold) {
        UUID s = shipmentWith(tolerance, "560001");
        seedForecast("560001", maxTemp, 0);
        assertThat(gate.evaluate(s).isHold()).isEqualTo(shouldHold);
    }

    @Test
    void parcelWithMixedContents_usesLowestTolerance() {
        UUID s = shipmentWithContents("560001", "LOW", "HIGH"); // fern + succulent, same box
        seedForecast("560001", 36.0, 0);
        assertThat(gate.evaluate(s).isHold()).isTrue(); // gated on the fern
    }

    @Test
    void floodAlert_holdsRegardlessOfTemperature() {
        UUID s = shipmentWith("HIGH", "400001");
        seedForecastWithAlert("400001", 28.0, 180, "FLOOD");
        assertThat(gate.evaluate(s).reason()).isEqualTo(HoldReason.RAIN);
    }

    @Test
    void staleForecastData_failsOpenWithAlert() {
        UUID s = shipmentWith("LOW", "560001");
        seedForecast("560001", 42.0, 0, hoursAgo(20)); // stale, and hot

        assertThat(gate.evaluate(s).isHold()).isFalse(); // proceeds anyway
        assertThat(alerts.captured()).anyMatch(a -> a.contains("stale"));
    }

    @Test
    void missingForecast_failsOpen_neverClosed() {
        UUID s = shipmentWith("LOW", "999999"); // no pincode_zones/coordinates/forecast for this destination at all
        assertThat(gate.evaluate(s).isHold()).isFalse();
    }

    @Test
    void shadowMode_recordsDecisionWithoutHolding() {
        setFlag("weather_gating", true, true);
        UUID s = shipmentWith("LOW", "560001");
        seedForecast("560001", 42.0, 0);

        assertThat(gate.evaluate(s).isHold()).isFalse();
        assertThat(holdRepo.findShadowFor(s)).isPresent();
    }
}
