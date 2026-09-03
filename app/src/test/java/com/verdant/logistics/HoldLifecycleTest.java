package com.verdant.logistics;

import com.verdant.common.alerting.Alerting;
import com.verdant.common.testing.AllocationFixtures;
import com.verdant.common.testing.IntegrationTestBase;
import com.verdant.logistics.weather.DispatchGate;
import com.verdant.logistics.weather.DispatchHoldRepository;
import com.verdant.logistics.weather.HoldOverrideService;
import com.verdant.logistics.weather.HoldReleaseJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class HoldLifecycleTest extends IntegrationTestBase {

    @Autowired private DispatchGate gate;
    @Autowired private DispatchHoldRepository holdRepo;
    @Autowired private HoldReleaseJob releaseJob;
    @Autowired private HoldOverrideService override;
    @Autowired private Alerting alerts;
    @Autowired private CacheManager cacheManager;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        alerts.clear();
        AllocationFixtures.seedLocalPincodeZone(jdbc);
        setFlag("weather_gating", true, false);
    }

    private void setFlag(String name, boolean enabled, boolean shadow) {
        jdbc.update("UPDATE feature_flags SET enabled = ?, shadow_mode = ? WHERE name = ?", enabled, shadow, name);
        var cache = cacheManager.getCache("flags");
        if (cache != null) cache.evict(name);
    }

    private UUID shipmentWith(UUID orderId, String heatTolerance, String destinationPincode) {
        UUID sellerId = AllocationFixtures.seedApprovedSeller(jdbc, "Hold Test Nursery " + UUID.randomUUID());
        UUID canonicalProductId = AllocationFixtures.seedCanonicalProduct(jdbc, "Hold Test Plant (" + heatTolerance + ")");
        jdbc.update("UPDATE canonical_products SET heat_tolerance = ? WHERE id = ?", heatTolerance, canonicalProductId);
        UUID listingId = AllocationFixtures.seedListing(jdbc, sellerId, canonicalProductId, "Hold Test Plant", "199.00", 5);
        jdbc.update("""
            INSERT INTO order_items (order_id, listing_id, seller_id, quantity, unit_price, line_total)
            VALUES (?, ?, ?, 1, 199.00, 199.00)
            """, orderId, listingId, sellerId);
        return jdbc.queryForObject("""
            INSERT INTO shipments (order_id, seller_id, status, origin_pincode, destination_pincode, transit_days)
            VALUES (?, ?, 'BOOKED', '411001', ?, 4) RETURNING id
            """, UUID.class, orderId, sellerId, destinationPincode);
    }

    private UUID newOrder(String destinationPincode) {
        UUID orderId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO orders (id, buyer_email, status, total_amount, currency, expires_at, destination_pincode)
            VALUES (?, 'hold-test@example.com', 'PAID', 199.00, 'INR', now() + interval '15 minutes', ?)
            """, orderId, destinationPincode);
        return orderId;
    }

    private void seedForecast(String pincode, double maxTempC, double rainfallMm) {
        jdbc.update("""
            INSERT INTO weather_forecasts (pincode, forecast_date, max_temp_c, min_temp_c, rainfall_mm, fetched_at)
            VALUES (?, ?, ?, ?, ?, now())
            ON CONFLICT (pincode, forecast_date) DO UPDATE SET max_temp_c = EXCLUDED.max_temp_c,
                rainfall_mm = EXCLUDED.rainfall_mm, fetched_at = now()
            """, pincode, LocalDate.now(), maxTempC, maxTempC - 8, rainfallMm);
    }

    private String shipmentStatus(UUID shipmentId) {
        return jdbc.queryForObject("SELECT status FROM shipments WHERE id = ?", String.class, shipmentId);
    }

    @Test
    void heldShipment_whenForecastImproves_autoReleases() {
        UUID order = newOrder("560001");
        UUID shipment = shipmentWith(order, "LOW", "560001");
        seedForecast("560001", 42.0, 0); // hot - holds
        gate.evaluate(shipment);
        assertThat(shipmentStatus(shipment)).isEqualTo("HELD");

        seedForecast("560001", 28.0, 0); // forecast improved
        UUID holdId = holdRepo.findActiveFor(shipment).orElseThrow();
        releaseJob.reviewOne(holdId);

        assertThat(shipmentStatus(shipment)).isEqualTo("READY");
        assertThat(holdRepo.findActiveFor(shipment)).isEmpty();
    }

    @Test
    void holdExceedingMaxDuration_escalatesToOps_andDoesNotAutoRelease() {
        UUID order = newOrder("560001");
        UUID shipment = shipmentWith(order, "LOW", "560001");
        seedForecast("560001", 42.0, 0); // still hot when reviewed - would not auto-release anyway
        gate.evaluate(shipment);
        UUID holdId = holdRepo.findActiveFor(shipment).orElseThrow();

        // Backdate past its own max_hold_until - simulates the review job
        // running long after the hold was placed, not a fresh CsvSource
        // scenario like DispatchGateTest.
        jdbc.update("UPDATE dispatch_holds SET max_hold_until = now() - interval '1 day' WHERE id = ?", holdId);

        releaseJob.reviewOne(holdId);

        assertThat(alerts.captured()).anyMatch(a -> a.contains("exceeded maximum duration"));
        assertThat(shipmentStatus(shipment)).isEqualTo("HELD"); // not auto-released
        assertThat(holdRepo.findActiveFor(shipment)).isPresent(); // still open, needs a human
    }

    @Test
    void concurrentReleaseRuns_releaseExactlyOnce() throws Exception {
        UUID order = newOrder("560001");
        UUID shipment = shipmentWith(order, "LOW", "560001");
        seedForecast("560001", 42.0, 0);
        gate.evaluate(shipment);
        UUID holdId = holdRepo.findActiveFor(shipment).orElseThrow();

        List<Integer> results = runInParallel(8, () -> holdRepo.releaseIfNotAlready(holdId, "system"));

        assertThat(results.stream().mapToInt(Integer::intValue).sum())
            .as("exactly one overlapping run must be the one that actually released it")
            .isEqualTo(1);
    }

    @Test
    void buyerOverride_shipsImmediately_andRecordsAcknowledgement() {
        UUID order = newOrder("560001");
        UUID shipment = shipmentWith(order, "LOW", "560001");
        seedForecast("560001", 42.0, 0); // still hot - the buyer is overriding despite the risk
        gate.evaluate(shipment);
        UUID holdId = holdRepo.findActiveFor(shipment).orElseThrow();

        boolean released = override.overrideAndShipNow(holdId, shipment);

        assertThat(released).isTrue();
        assertThat(shipmentStatus(shipment)).isEqualTo("READY");
        Long acknowledgements = jdbc.queryForObject(
            "SELECT count(*) FROM notifications WHERE shipment_id = ? AND event_type = 'HOLD_OVERRIDE_ACKNOWLEDGED'",
            Long.class, shipment);
        assertThat(acknowledgements).isEqualTo(1L);
    }

    @Test
    void heldShipment_doesNotBlockOtherShipmentsInSameOrder() {
        UUID order = newOrder("560001");
        UUID heldShipment = shipmentWith(order, "LOW", "560001");   // fern - gated
        UUID okShipment = shipmentWith(order, "HIGH", "560001");    // succulent - tolerates it
        seedForecast("560001", 36.0, 0); // hot enough for LOW, not for HIGH

        gate.evaluate(heldShipment);
        gate.evaluate(okShipment);

        assertThat(shipmentStatus(heldShipment)).isEqualTo("HELD");
        assertThat(shipmentStatus(okShipment)).isEqualTo("BOOKED"); // untouched - proceeded normally
        assertThat(holdRepo.findActiveFor(okShipment)).isEmpty();
    }

    private List<Integer> runInParallel(int n, java.util.concurrent.Callable<Integer> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return task.call();
            }));
        }
        start.countDown();

        List<Integer> results = new java.util.ArrayList<>();
        for (Future<Integer> f : futures) {
            try {
                results.add(f.get());
            } catch (ExecutionException e) {
                pool.shutdown();
                throw (Exception) e.getCause();
            }
        }
        pool.shutdown();
        return results;
    }
}
