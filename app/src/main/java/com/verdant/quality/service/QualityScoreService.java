package com.verdant.quality.service;

import com.verdant.common.notification.NotificationService;
import com.verdant.common.outbox.OutboxWriter;
import com.verdant.quality.domain.Component;
import com.verdant.quality.domain.QualityScore;
import com.verdant.quality.domain.Tier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Directly callable, synchronous entry point - same reasoning as every
 * other @Scheduled job in this project that also exposes a plain method
 * (PayoutCycleBuildJob.buildCyclesFor(), ReconciliationJob.reconcile()):
 * QualityRecomputeJob (Day 54) just loops this over every active seller,
 * as a genuine cross-bean call - deliberately NOT doing the notify/
 * outbox/writeback work inline in the job's own loop body, which would
 * need @Transactional on a method the job calls via plain "this." self-
 * invocation from its own unannotated recomputeAll() loop, silently
 * skipping the proxy (the exact InventoryService.reserve() class of bug,
 * found and fixed earlier this same week) - keeping it here instead,
 * where the job's call is a real cross-bean call the AOP proxy actually
 * intercepts, avoids that trap by construction rather than by care.
 *
 * The STORED tier is computed from the DAMPED score, not QualityScorer's
 * own raw target.tier() - damping exists specifically so one bad week
 * cannot cross a tier boundary (53.2's own stated design goal), and
 * using the undamped tier here would silently defeat that the moment a
 * seller's raw number crossed a threshold, even though their damped
 * score never did.
 */
@Service
public class QualityScoreService {

    private final QualityScorer scorer;
    private final SellerQualityScoreRepository scores;
    private final SellerQualityHistoryRepository history;
    private final NotificationService notifications;
    private final OutboxWriter outbox;
    private final JdbcTemplate jdbc;

    public QualityScoreService(QualityScorer scorer, SellerQualityScoreRepository scores,
                                SellerQualityHistoryRepository history, NotificationService notifications,
                                OutboxWriter outbox, JdbcTemplate jdbc) {
        this.scorer = scorer;
        this.scores = scores;
        this.history = history;
        this.notifications = notifications;
        this.outbox = outbox;
        this.jdbc = jdbc;
    }

    /** @Transactional: outbox.write() uses MANDATORY propagation (Week 6) - needs a real enclosing transaction, same requirement every other outbox call site in this project has already had to satisfy. */
    @Transactional
    public RecomputeResult recompute(UUID sellerId) {
        QualityScore target = scorer.compute(sellerId);
        Optional<SellerQualityScoreRepository.CurrentScore> current = scores.findBySellerId(sellerId);

        double appliedScore = current
            .map(c -> scorer.applyDamping(c.score(), target.value().doubleValue()))
            .orElse(target.value().doubleValue());
        Tier appliedTier = scorer.tierFor(appliedScore, target.isProvisional());

        // No prior record at all (this seller's first-ever computation) is
        // an initial state, not a "change" worth notifying/reindexing over -
        // the fan-out below exists to propagate an actual transition.
        boolean tierChanged = current.map(c -> c.tier() != appliedTier).orElse(false);

        Map<String, Object> componentsJson = toJson(target.components());
        scores.upsert(sellerId, appliedScore, componentsJson, target.orderCount(), target.isProvisional(), appliedTier,
            current.map(SellerQualityScoreRepository.CurrentScore::score).orElse(null),
            current.map(SellerQualityScoreRepository.CurrentScore::tier).orElse(null));
        history.record(sellerId, appliedScore, appliedTier, componentsJson);

        // Real wiring into allocation and ranking (Day 54): sellers.quality_score
        // is the SAME column CandidateFinder (Week 6 allocation) and
        // ProjectionQueryRepository (Week 9 search) already read - writing
        // the real, damped, six-signal score back here means both start
        // receiving it with zero changes needed at either consumer, rather
        // than introducing a second, competing notion of "seller quality".
        jdbc.update("UPDATE sellers SET quality_score = ? WHERE id = ?",
            BigDecimal.valueOf(appliedScore).setScale(2, java.math.RoundingMode.HALF_UP), sellerId);

        if (tierChanged) {
            notifications.notifySeller(sellerId, "QUALITY_TIER_CHANGED",
                "New tier: " + appliedTier + " (score " + BigDecimal.valueOf(appliedScore).setScale(2, java.math.RoundingMode.HALF_UP) + ")");
            outbox.write("SELLER", sellerId, "SELLER_QUALITY_CHANGED", Map.of("sellerId", sellerId.toString()));
        }

        return new RecomputeResult(sellerId, appliedScore, appliedTier, tierChanged, target.isProvisional());
    }

    private Map<String, Object> toJson(Map<String, Component> components) {
        Map<String, Object> json = new LinkedHashMap<>();
        components.forEach((name, c) -> json.put(name, Map.of(
            "numerator", c.numerator(), "denominator", c.denominator(),
            "description", c.description(), "value", c.value())));
        return json;
    }

    public record RecomputeResult(UUID sellerId, double score, Tier tier, boolean tierChanged, boolean isProvisional) { }
}
