package com.verdant.observability;

import com.verdant.common.testing.IntegrationTestBase;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every gauge here is a REAL, GLOBAL count across the whole shared
 * Testcontainers database (BusinessMetrics has no per-test scope to
 * filter by - that's the real nature of a business gauge). Asserting
 * an absolute value would be exactly the exact-count fragility this
 * project's own search facet tests already hit and fixed (Week 9) -
 * every scenario here instead seeds N new rows and asserts the gauge
 * moved by exactly N between a refresh() before and after, regardless
 * of whatever every other test in the suite has already left behind.
 */
class BusinessMetricsTest extends IntegrationTestBase {

    @Autowired private BusinessMetrics metrics;
    @Autowired private MeterRegistry meters;
    @Autowired private JdbcTemplate jdbc;

    private double gauge(String name, String tagKey, String tagValue) {
        return meters.find(name).tag(tagKey, tagValue).gauge().value();
    }

    @Test
    void sellersByStatus_reflectsARealNewlySeededRow() {
        metrics.refresh();
        double before = gauge("verdant.sellers", "status", "SUSPENDED");

        jdbc.update("INSERT INTO sellers (business_name, status) VALUES (?, 'SUSPENDED')",
            "Metrics Test Nursery " + UUID.randomUUID());
        metrics.refresh();

        assertThat(gauge("verdant.sellers", "status", "SUSPENDED")).isEqualTo(before + 1);
    }

    @Test
    void sellersByTier_reflectsARealNewlySeededRow() {
        metrics.refresh();
        double before = gauge("verdant.sellers.by_tier", "tier", "RESTRICTED");

        UUID sellerId = jdbc.queryForObject(
            "INSERT INTO sellers (business_name, status) VALUES (?, 'APPROVED') RETURNING id",
            UUID.class, "Metrics Tier Test Nursery " + UUID.randomUUID());
        jdbc.update("""
            INSERT INTO seller_quality_scores (seller_id, score, components, order_count, is_provisional, tier)
            VALUES (?, 0.10, '{}'::jsonb, 5, false, 'RESTRICTED')
            """, sellerId);
        metrics.refresh();

        assertThat(gauge("verdant.sellers.by_tier", "tier", "RESTRICTED")).isEqualTo(before + 1);
    }

    @Test
    void ordersPaidToday_reflectsARealPaidOrder_notAnUnpaidOne() {
        metrics.refresh();
        double before = meters.find("verdant.orders.paid_today").gauge().value();

        jdbc.update("""
            INSERT INTO orders (buyer_email, status, total_amount, expires_at, paid_at)
            VALUES ('metrics-buyer@example.com', 'PAID', 300.00, now() + interval '15 minutes', now())
            """);
        jdbc.update("""
            INSERT INTO orders (buyer_email, status, total_amount, expires_at, paid_at)
            VALUES ('metrics-buyer-2@example.com', 'CREATED', 300.00, now() + interval '15 minutes', null)
            """);
        metrics.refresh();

        assertThat(meters.find("verdant.orders.paid_today").gauge().value()).isEqualTo(before + 1);
    }

    @Test
    void assistantInteractions_splitsAnsweredFromRefused() {
        metrics.refresh();
        double answeredBefore = gauge("verdant.assistant.interactions_24h", "outcome", "ANSWERED");
        double refusedBefore = gauge("verdant.assistant.interactions_24h", "outcome", "REFUSED");

        jdbc.update("""
            INSERT INTO assistant_interactions (feature, user_subject_id, question, outcome, top_similarity, latency_ms)
            VALUES ('GIFT_CONCIERGE', 'metrics-test-user', 'test question', 'ANSWERED', 0.9, 50)
            """);
        jdbc.update("""
            INSERT INTO assistant_interactions (feature, user_subject_id, question, outcome, top_similarity, latency_ms)
            VALUES ('GIFT_CONCIERGE', 'metrics-test-user', 'test question', 'REFUSED_OUT_OF_SCOPE', null, 5)
            """);
        metrics.refresh();

        assertThat(gauge("verdant.assistant.interactions_24h", "outcome", "ANSWERED")).isEqualTo(answeredBefore + 1);
        assertThat(gauge("verdant.assistant.interactions_24h", "outcome", "REFUSED")).isEqualTo(refusedBefore + 1);
    }
}
