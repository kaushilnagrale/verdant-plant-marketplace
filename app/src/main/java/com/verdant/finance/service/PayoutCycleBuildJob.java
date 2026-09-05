package com.verdant.finance.service;

import com.verdant.common.alerting.Alerting;
import com.verdant.common.flags.FeatureFlags;
import com.verdant.finance.domain.PayoutCycle;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/**
 * Neither Day 44 nor Day 47 specifies what builds a cycle automatically -
 * PayoutService.buildCycle() just takes explicit dates, and Day 47's own
 * "cycles awaiting approval" query presumes something already put rows
 * into PENDING_APPROVAL. This is that something.
 *
 * Weekly, Monday-Sunday IST: matches the scale PayoutEligibility's own
 * windows already assume (a 72h claim window, an 8-day COD remittance
 * lag) and the common real-world default for marketplace seller payouts.
 * Always builds for the week that just fully closed (never the current,
 * still-running week), so every item's delivered_at is settled history,
 * not something that could still change.
 */
@Component
public class PayoutCycleBuildJob {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final String SYSTEM_ACTOR = "system:auto_payout";

    private final JdbcTemplate jdbc;
    private final PayoutService payoutService;
    private final FeatureFlags featureFlags;
    private final Alerting alerting;

    public PayoutCycleBuildJob(JdbcTemplate jdbc, PayoutService payoutService, FeatureFlags featureFlags,
                                Alerting alerting) {
        this.jdbc = jdbc;
        this.payoutService = payoutService;
        this.featureFlags = featureFlags;
        this.alerting = alerting;
    }

    @Scheduled(cron = "${verdant.payout.build-cron:0 0 5 * * MON}")
    public void buildLastWeeksCycles() {
        LocalDate today = LocalDate.now(IST);
        LocalDate periodEnd = today.with(DayOfWeek.MONDAY).minusDays(1);   // last Sunday
        LocalDate periodStart = periodEnd.minusDays(6);                    // the Monday before it
        buildCyclesFor(periodStart, periodEnd);
    }

    /**
     * Exposed with explicit dates - not just the @Scheduled entry point -
     * so a test can build a specific, fully-controlled period instead of
     * depending on real wall-clock "this week" (same reasoning as
     * ReconciliationJob.reconcile(LocalDate), Day 46).
     *
     * REAL BUG, found by a test's own accumulated fixture data rather than
     * proactively: the original version called processOneSeller() directly
     * inline, with no per-seller isolation - one seller throwing (a real
     * InsufficientPayableException, or literally any other exception) took
     * down the ENTIRE weekly batch, silently skipping every seller after
     * it in iteration order for the whole week. Same shape of bug
     * ForecastRefreshJob (Week 7) was already built to avoid for its own
     * per-pincode batch - quarantine one unit's failure, keep going for
     * everyone else, alert loudly rather than fail silently or fail
     * everything.
     */
    public void buildCyclesFor(LocalDate periodStart, LocalDate periodEnd) {
        for (UUID sellerId : sellersWithDeliveriesIn(periodStart, periodEnd)) {
            try {
                processOneSeller(sellerId, periodStart, periodEnd);
            } catch (RuntimeException e) {
                alerting.raise("Payout cycle build failed for seller " + sellerId
                    + " (" + periodStart + " to " + periodEnd + "): " + e.getMessage());
            }
        }
    }

    private void processOneSeller(UUID sellerId, LocalDate periodStart, LocalDate periodEnd) {
        PayoutCycle cycle = payoutService.buildCycle(sellerId, periodStart, periodEnd);

        // buildCycle() itself has no early-return for "nothing was
        // actually eligible" (claim windows/open claims can still filter
        // everything out even after the coarse pre-filter above) - a
        // rare, harmless empty PENDING_APPROVAL row is an accepted,
        // documented limitation rather than a reason to change
        // already-tested Day 44 code for a cosmetic edge case.
        if (cycle.lineItems().isEmpty()) {
            return;
        }

        if (featureFlags.get("auto_payout").active() && !cycle.netAmount().isZero()) {
            payoutService.approve(cycle.id(), SYSTEM_ACTOR);
            payoutService.initiate(cycle.id(), SYSTEM_ACTOR);
        }
    }

    /** Coarse pre-filter (has anything delivered in the window at all) - PayoutEligibility does the real, fine-grained filtering inside buildCycle() itself. */
    private List<UUID> sellersWithDeliveriesIn(LocalDate start, LocalDate end) {
        return jdbc.queryForList("""
            SELECT DISTINCT oi.seller_id
              FROM order_items oi
              JOIN shipments sh ON sh.order_id = oi.order_id AND sh.seller_id = oi.seller_id
              JOIN sellers s ON s.id = oi.seller_id
             WHERE s.status = 'APPROVED' AND sh.delivered_at IS NOT NULL AND sh.delivered_at::date BETWEEN ? AND ?
            """, UUID.class, start, end);
    }
}
