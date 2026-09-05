package com.verdant.forecasting.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A complete daily series over the window, zero-filled on days with no
 * sale - not just the days that happen to have an order_items row. A
 * median computed only over "days that sold something" would be
 * systematically inflated (every included day already has >=1 unit),
 * silently hiding exactly the sparse-demand case DemandForecaster most
 * needs to represent honestly. generate_series + LEFT JOIN builds that
 * complete series in one real query rather than post-processing gaps in
 * Java.
 */
@Repository
public class DemandHistoryRepository {

    private final JdbcTemplate jdbc;

    public DemandHistoryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record DailyUnits(LocalDate day, int units) { }

    /** [windowStart, windowEndExclusive) - half-open, same convention as this project's own transit/settlement date-range queries elsewhere. */
    public List<DailyUnits> dailyUnits(UUID canonicalProductId, LocalDate windowStart, LocalDate windowEndExclusive) {
        return jdbc.query("""
            WITH days AS (
                SELECT generate_series(?::date, (?::date - interval '1 day'), interval '1 day')::date AS day
            ),
            demand AS (
                SELECT date_trunc('day', o.paid_at)::date AS day, SUM(oi.quantity) AS units
                  FROM order_items oi
                  JOIN orders o ON o.id = oi.order_id
                  JOIN listings l ON l.id = oi.listing_id
                 WHERE l.canonical_product_id = ?
                   AND o.status = 'PAID'
                   AND o.paid_at >= ? AND o.paid_at < ?
                 GROUP BY 1
            )
            SELECT days.day, COALESCE(demand.units, 0)::int AS units
              FROM days LEFT JOIN demand ON demand.day = days.day
             ORDER BY days.day
            """,
            (rs, rowNum) -> new DailyUnits(rs.getObject("day", LocalDate.class), rs.getInt("units")),
            windowStart, windowEndExclusive, canonicalProductId, windowStart, windowEndExclusive);
    }
}
