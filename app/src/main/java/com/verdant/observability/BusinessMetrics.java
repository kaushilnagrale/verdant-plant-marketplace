package com.verdant.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Real business gauges (Week 12 observability), deliberately DB-polled
 * rather than instrumented into existing hot-path code: a gauge whose
 * value comes from a periodic read-only query can't change the
 * behavior or latency of the order/checkout/allocation paths it
 * describes, unlike threading a new Counter.increment() into code
 * that's been working and tested for 12 weeks. search.index.lag
 * (Week 9) and doctor.pesticide.blocked (Week 11) are the exception to
 * that, and only because they were instrumented into code being
 * written for the first time, not retrofitted into existing code.
 *
 * refresh() is directly callable (BusinessMetricsTest calls it
 * synchronously) - same "suppress the real interval in tests, keep the
 * method itself callable" pattern as every other @Scheduled job in
 * this project (DispatchOrchestrationJob, NotificationSender, ...).
 */
@Component
public class BusinessMetrics {

    private static final List<String> SELLER_STATUSES =
        List.of("DRAFT", "PENDING_REVIEW", "APPROVED", "SUSPENDED", "OFFBOARDED");
    private static final List<String> SELLER_TIERS =
        List.of("PREFERRED", "STANDARD", "PROBATION", "RESTRICTED");

    private final JdbcTemplate jdbc;
    private final Map<String, AtomicInteger> sellersByStatus = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> sellersByTier = new ConcurrentHashMap<>();
    private final AtomicInteger ordersPaidToday = new AtomicInteger();
    private final AtomicInteger assistantAnsweredLast24h = new AtomicInteger();
    private final AtomicInteger assistantRefusedLast24h = new AtomicInteger();

    public BusinessMetrics(JdbcTemplate jdbc, MeterRegistry meters) {
        this.jdbc = jdbc;
        for (String status : SELLER_STATUSES) {
            AtomicInteger value = sellersByStatus.computeIfAbsent(status, s -> new AtomicInteger());
            Gauge.builder("verdant.sellers", value, AtomicInteger::get)
                .tag("status", status)
                .description("Real seller count by onboarding status, polled from sellers")
                .register(meters);
        }
        for (String tier : SELLER_TIERS) {
            AtomicInteger value = sellersByTier.computeIfAbsent(tier, t -> new AtomicInteger());
            Gauge.builder("verdant.sellers.by_tier", value, AtomicInteger::get)
                .tag("tier", tier)
                .description("Real seller count by quality tier, polled from seller_quality_scores")
                .register(meters);
        }
        Gauge.builder("verdant.orders.paid_today", ordersPaidToday, AtomicInteger::get)
            .description("Real count of orders paid since local midnight")
            .register(meters);
        Gauge.builder("verdant.assistant.interactions_24h", assistantAnsweredLast24h, AtomicInteger::get)
            .tag("outcome", "ANSWERED")
            .description("Real assistant interaction count in the last 24h, by outcome")
            .register(meters);
        Gauge.builder("verdant.assistant.interactions_24h", assistantRefusedLast24h, AtomicInteger::get)
            .tag("outcome", "REFUSED")
            .register(meters);
    }

    @Scheduled(fixedDelayString = "${verdant.metrics.interval-ms:30000}")
    public void refresh() {
        for (String status : SELLER_STATUSES) {
            sellersByStatus.get(status).set(nullToZero(jdbc.queryForObject(
                "SELECT count(*) FROM sellers WHERE status = ?", Integer.class, status)));
        }
        for (String tier : SELLER_TIERS) {
            sellersByTier.get(tier).set(nullToZero(jdbc.queryForObject(
                "SELECT count(*) FROM seller_quality_scores WHERE tier = ?", Integer.class, tier)));
        }
        ordersPaidToday.set(nullToZero(jdbc.queryForObject(
            "SELECT count(*) FROM orders WHERE status = 'PAID' AND paid_at >= date_trunc('day', now())", Integer.class)));
        assistantAnsweredLast24h.set(nullToZero(jdbc.queryForObject("""
            SELECT count(*) FROM assistant_interactions
             WHERE outcome = 'ANSWERED' AND created_at >= now() - interval '24 hours'
            """, Integer.class)));
        assistantRefusedLast24h.set(nullToZero(jdbc.queryForObject("""
            SELECT count(*) FROM assistant_interactions
             WHERE outcome IN ('REFUSED_NO_RETRIEVAL', 'REFUSED_OUT_OF_SCOPE') AND created_at >= now() - interval '24 hours'
            """, Integer.class)));
    }

    private int nullToZero(Integer value) {
        return value == null ? 0 : value;
    }
}
