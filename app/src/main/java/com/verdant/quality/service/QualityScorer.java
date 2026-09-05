package com.verdant.quality.service;

import com.verdant.quality.domain.Component;
import com.verdant.quality.domain.QualityScore;
import com.verdant.quality.domain.QualityStats;
import com.verdant.quality.domain.Tier;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Rules based, not a model - two reasons that matter enough to state
 * explicitly (53's own framing): the seller must be able to understand
 * and act on the score, and an unexplainable penalty on someone's
 * livelihood is not acceptable. Every component below carries the real
 * counts behind it (Component), not just a contribution number.
 *
 * Real, honestly-open gap, not silently pretended away: sellerCancellations,
 * totalOpsRequests and fastResponses are always 0 today - no real
 * producer exists anywhere in this project yet for a seller-attributed
 * order cancellation or for any support-ticket/response-time tracking
 * (see QualityStatsRepository's own comment). cancellationRate and
 * responsiveness are therefore always 1.0 (perfect) until that real
 * infrastructure is built - correctly reads as "no data yet", not as a
 * false claim of good behavior, but worth remembering before quoting a
 * seller's overall score as fully evidenced on every axis.
 */
@Service
public class QualityScorer {

    private static final Duration WINDOW = Duration.ofDays(60);
    private static final int PROVISIONAL_ORDER_THRESHOLD = 20;
    private static final double NEUTRAL_START = 0.70;
    private static final double MAX_MOVEMENT_PER_COMPUTATION = 0.10;

    private static final Map<String, Double> WEIGHTS = Map.of(
        "dispatchAdherence", 0.30,
        "cancellationRate", 0.25,
        "claimRate", 0.25,
        "rtoRate", 0.10,
        "responsiveness", 0.10
    );

    private final QualityStatsRepository repo;

    public QualityScorer(QualityStatsRepository repo) {
        this.repo = repo;
    }

    public QualityScore compute(UUID sellerId) {
        QualityStats stats = repo.statsForWindow(sellerId, WINDOW);

        Map<String, Component> components = new LinkedHashMap<>();
        components.put("dispatchAdherence", Component.of(
            stats.onTimeDispatches(), stats.totalDispatches(),
            "Orders dispatched within your stated handling time"));
        components.put("cancellationRate", Component.inverse(
            stats.sellerCancellations(), stats.totalOrders(),
            "Orders you cancelled after accepting"));
        components.put("claimRate", Component.inverse(
            stats.claimsUpheld(), stats.totalDelivered(),
            "Deliveries where the buyer reported a problem and it was found to be seller-side"));
        components.put("rtoRate", Component.inverse(
            stats.rtoCount(), stats.totalDispatches(),
            "Parcels returned undelivered"));
        components.put("responsiveness", Component.of(
            stats.fastResponses(), stats.totalOpsRequests(),
            "Replies to platform messages within 24 hours"));

        double raw = components.entrySet().stream()
            .mapToDouble(e -> WEIGHTS.get(e.getKey()) * e.getValue().value())
            .sum();

        boolean provisional = stats.totalOrders() < PROVISIONAL_ORDER_THRESHOLD;

        // New sellers start at neutral and move toward their measured score
        // gradually - judging someone on three data points is noise, not signal.
        double score = provisional
            ? blend(NEUTRAL_START, raw, (double) stats.totalOrders() / PROVISIONAL_ORDER_THRESHOLD)
            : raw;

        return QualityScore.of(sellerId, score, components,
            stats.totalOrders(), provisional, tierFor(score, provisional));
    }

    private double blend(double neutral, double target, double fraction) {
        return neutral + (target - neutral) * fraction;
    }

    /** Caps movement at 0.10 per computation, so one bad week cannot destroy a good seller - real world variance is high, and a nursery having a rough fortnight during monsoon is not the same as a bad seller. */
    public double applyDamping(double previous, double target) {
        double delta = target - previous;
        return previous + Math.signum(delta) * Math.min(Math.abs(delta), MAX_MOVEMENT_PER_COMPUTATION);
    }

    /**
     * Public and reusable independent of a fresh compute() call: the
     * final, STORED tier must be computed from the damped score, not the
     * raw target (see QualityScoreService.recompute()) - otherwise
     * damping would cap the score's own movement but let the tier jump
     * straight to what the undamped number implied anyway, defeating the
     * entire point of damping in the first place.
     */
    public Tier tierFor(double score, boolean provisional) {
        if (provisional) {
            return Tier.PROBATION;
        }
        if (score >= 0.85) {
            return Tier.PREFERRED;
        }
        if (score >= 0.60) {
            return Tier.STANDARD;
        }
        return Tier.RESTRICTED;
    }
}
