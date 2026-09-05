package com.verdant.forecasting.service;

import com.verdant.forecasting.domain.Forecast;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Seasonal-naive + trend (62.2), median not mean for the baseline: this
 * project's own order history is short and lumpy (a single bulk order
 * can be many units at once - InventoryConcurrencyTest's own fixtures do
 * exactly that), and a mean baseline would let one such day dominate a
 * "typical day" estimate the way a median naturally resists.
 *
 * Real, explicit assumption, documented rather than silently guessed:
 * FESTIVAL_BOOST is a fixed planning multiplier, not something empirically
 * derived from this project's own history - there isn't yet a full real
 * season of order data spanning an actual festival to fit one from. This
 * should be replaced with a real, measured uplift once that data exists;
 * until then it's a labeled assumption, not a hidden one.
 */
@Service
public class DemandForecaster {

    private static final int LOOKBACK_DAYS = 28;
    private static final double FESTIVAL_BOOST = 1.6;
    private static final double MIN_TREND = 0.5;
    private static final double MAX_TREND = 2.0;

    private final DemandHistoryRepository history;
    private final FestivalCalendarRepository festivals;

    public DemandForecaster(DemandHistoryRepository history, FestivalCalendarRepository festivals) {
        this.history = history;
        this.festivals = festivals;
    }

    public Forecast forecast(UUID canonicalProductId, LocalDate targetDate) {
        LocalDate windowStart = targetDate.minusDays(LOOKBACK_DAYS);
        List<DemandHistoryRepository.DailyUnits> daily = history.dailyUnits(canonicalProductId, windowStart, targetDate);

        List<Integer> allUnits = daily.stream().map(DemandHistoryRepository.DailyUnits::units).toList();
        int baseline = (int) Math.round(median(allUnits));

        int half = daily.size() / 2;
        double olderMedian = median(allUnits.subList(0, half));
        double newerMedian = median(allUnits.subList(half, allUnits.size()));
        double trend = trendFactor(olderMedian, newerMedian);

        Optional<FestivalCalendarRepository.Festival> festival = festivals.festivalCovering(targetDate);
        double festivalMultiplier = festival.isPresent() ? FESTIVAL_BOOST : 1.0;

        int forecastUnits = Math.max(0, (int) Math.round(baseline * trend * festivalMultiplier));

        return Forecast.of(canonicalProductId, targetDate, baseline, trend,
            festival.isPresent(), festival.map(FestivalCalendarRepository.Festival::name).orElse(null), forecastUnits);
    }

    /** No historical data at all still returns a real answer (0), never NaN or a crash - an empty window is a legitimate real state (a brand-new product), not an error. */
    private double median(List<Integer> values) {
        if (values.isEmpty()) {
            return 0.0;
        }
        List<Integer> sorted = new ArrayList<>(values);
        sorted.sort(null);
        int mid = sorted.size() / 2;
        if (sorted.size() % 2 == 0) {
            return (sorted.get(mid - 1) + sorted.get(mid)) / 2.0;
        }
        return sorted.get(mid);
    }

    /** Clamped so a noisy small-sample swing (or a jump from zero) can't extrapolate into an absurd forecast - a real safeguard, not just a formula. */
    private double trendFactor(double olderMedian, double newerMedian) {
        if (olderMedian == 0.0) {
            return newerMedian == 0.0 ? 1.0 : MAX_TREND;
        }
        double raw = newerMedian / olderMedian;
        return Math.max(MIN_TREND, Math.min(MAX_TREND, raw));
    }
}
