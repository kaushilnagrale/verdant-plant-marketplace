package com.verdant.forecasting;

import com.verdant.common.testing.AllocationFixtures;
import com.verdant.common.testing.IntegrationTestBase;
import com.verdant.forecasting.domain.Forecast;
import com.verdant.forecasting.service.DemandForecaster;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every scenario seeds its own fresh canonical product (AllocationFixtures.seedCanonicalProduct,
 * not a shared real one like "Money Plant") - this project's own repeated
 * lesson about shared-Testcontainers-state pollution (Week 9's search
 * corpus, this week's own NotificationTest quiet-hours fix) applies just
 * as much here: forecasting reads ALL historical orders for a product
 * id, so reusing a real catalog product any other test might also seed
 * orders against would make these assertions depend on suite order.
 */
class ForecastTest extends IntegrationTestBase {

    @Autowired private DemandForecaster forecaster;
    @Autowired private JdbcTemplate jdbc;

    // Clear of every seeded festival_calendar row's own lead window
    // (Raksha Bandhan 2026-08-28 -7d, Diwali 2026-11-08 -10d, Holi
    // 2027-03-04 -5d) - a plain, unboosted baseline for scenarios 1-3 and 5.
    private static final LocalDate QUIET_DATE = LocalDate.of(2026, 1, 15);

    private record Seeded(UUID sellerId, UUID productId, UUID listingId) { }

    private Seeded seed() {
        UUID sellerId = AllocationFixtures.seedApprovedSeller(jdbc, "Forecast Nursery " + UUID.randomUUID());
        UUID productId = AllocationFixtures.seedCanonicalProduct(jdbc, "Forecast Test Plant");
        UUID listingId = AllocationFixtures.seedListing(jdbc, sellerId, productId, "Forecast test listing", "300.00", 999);
        return new Seeded(sellerId, productId, listingId);
    }

    private void seedDailyDemand(Seeded s, LocalDate fromInclusive, LocalDate toExclusive, int unitsPerDay) {
        if (unitsPerDay <= 0) {
            return;
        }
        for (LocalDate d = fromInclusive; d.isBefore(toExclusive); d = d.plusDays(1)) {
            seedPaidOrder(s, unitsPerDay, d);
        }
    }

    private void seedPaidOrder(Seeded s, int quantity, LocalDate paidOn) {
        Timestamp paidAt = Timestamp.from(paidOn.atTime(10, 0).toInstant(ZoneOffset.UTC));
        Timestamp expiresAt = Timestamp.from(paidOn.atTime(10, 15).toInstant(ZoneOffset.UTC));
        UUID orderId = jdbc.queryForObject("""
            INSERT INTO orders (buyer_email, status, total_amount, expires_at, paid_at)
            VALUES ('forecast-buyer@example.com', 'PAID', 300.00, ?, ?)
            RETURNING id
            """, UUID.class, expiresAt, paidAt);
        jdbc.update("""
            INSERT INTO order_items (order_id, listing_id, seller_id, quantity, unit_price, line_total)
            VALUES (?, ?, ?, ?, 300.00, ?)
            """, orderId, s.listingId(), s.sellerId(), quantity, new BigDecimal("300.00").multiply(BigDecimal.valueOf(quantity)));
    }

    @Test
    void flatHistory_forecastsTheSameFlatLevel_noBoost() {
        Seeded s = seed();
        seedDailyDemand(s, QUIET_DATE.minusDays(28), QUIET_DATE, 5);

        Forecast forecast = forecaster.forecast(s.productId(), QUIET_DATE);

        assertThat(forecast.baselineUnits()).isEqualTo(5);
        assertThat(forecast.forecastUnits()).isEqualTo(5);
        assertThat(forecast.festivalBoosted()).isFalse();
    }

    @Test
    void upwardTrend_forecastsAboveThePlainBaseline() {
        Seeded s = seed();
        seedDailyDemand(s, QUIET_DATE.minusDays(28), QUIET_DATE.minusDays(14), 3); // older half
        seedDailyDemand(s, QUIET_DATE.minusDays(14), QUIET_DATE, 9);               // newer half

        Forecast forecast = forecaster.forecast(s.productId(), QUIET_DATE);

        assertThat(forecast.trendFactor()).isGreaterThan(BigDecimal.ONE);
        assertThat(forecast.forecastUnits()).isGreaterThan(forecast.baselineUnits());
    }

    @Test
    void downwardTrend_forecastsBelowBaseline_neverNegative() {
        Seeded s = seed();
        seedDailyDemand(s, QUIET_DATE.minusDays(28), QUIET_DATE.minusDays(14), 10); // older half
        // newer half left at zero - demand dried up entirely

        Forecast forecast = forecaster.forecast(s.productId(), QUIET_DATE);

        assertThat(forecast.trendFactor()).isLessThan(BigDecimal.ONE);
        assertThat(forecast.forecastUnits()).isLessThan(forecast.baselineUnits());
        assertThat(forecast.forecastUnits()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void dateWithinFestivalLeadWindow_isBoosted() {
        Seeded s = seed();
        LocalDate diwaliAdjacent = LocalDate.of(2026, 11, 3); // Diwali 2026-11-08, lead 10 days - real seed row, V38
        seedDailyDemand(s, diwaliAdjacent.minusDays(28), diwaliAdjacent, 4);

        Forecast forecast = forecaster.forecast(s.productId(), diwaliAdjacent);

        assertThat(forecast.festivalBoosted()).isTrue();
        assertThat(forecast.festivalName()).isEqualTo("Diwali");
        assertThat(forecast.forecastUnits()).isGreaterThan(forecast.baselineUnits());
    }

    @Test
    void brandNewProduct_withNoOrderHistory_forecastsZero_ratherThanFailing() {
        Seeded s = seed(); // no seedDailyDemand call at all - zero real orders exist

        Forecast forecast = forecaster.forecast(s.productId(), QUIET_DATE);

        assertThat(forecast.baselineUnits()).isZero();
        assertThat(forecast.forecastUnits()).isZero();
    }
}
