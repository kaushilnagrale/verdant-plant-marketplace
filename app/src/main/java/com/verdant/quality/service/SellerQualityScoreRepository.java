package com.verdant.quality.service;

import com.verdant.quality.domain.Tier;
import org.json.JSONObject;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class SellerQualityScoreRepository {

    private final JdbcTemplate jdbc;

    public SellerQualityScoreRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<CurrentScore> findBySellerId(UUID sellerId) {
        try {
            Map<String, Object> row = jdbc.queryForMap(
                "SELECT score, tier FROM seller_quality_scores WHERE seller_id = ?", sellerId);
            return Optional.of(new CurrentScore(
                ((Number) row.get("score")).doubleValue(), Tier.valueOf((String) row.get("tier"))));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /** Upsert - a seller's first ever computation and every one after it both land here, ON CONFLICT distinguishing them (previous_score/previous_tier only meaningfully set on the update path). */
    public void upsert(UUID sellerId, double score, Map<String, Object> componentsJson,
                        int orderCount, boolean isProvisional, Tier tier,
                        Double previousScore, Tier previousTier) {
        jdbc.update("""
            INSERT INTO seller_quality_scores
                (seller_id, score, components, order_count, is_provisional, tier, previous_score, previous_tier)
            VALUES (?, ?, ?::jsonb, ?, ?, ?, ?, ?)
            ON CONFLICT (seller_id) DO UPDATE SET
                score = EXCLUDED.score, components = EXCLUDED.components, order_count = EXCLUDED.order_count,
                is_provisional = EXCLUDED.is_provisional, tier = EXCLUDED.tier,
                previous_score = EXCLUDED.previous_score, previous_tier = EXCLUDED.previous_tier,
                computed_at = now()
            """, sellerId, score, new JSONObject(componentsJson).toString(), orderCount, isProvisional,
            tier.name(), previousScore, previousTier == null ? null : previousTier.name());
    }

    /** The seller-facing quality view (Day 56's own "Payouts and quality" screen) - the full row, components included, not just the score/tier CurrentScore carries for the scorer's own internal damping use. */
    public Optional<SellerQualityView> findFullBySellerId(UUID sellerId) {
        try {
            Map<String, Object> row = jdbc.queryForMap("""
                SELECT score, tier, components, order_count, is_provisional, computed_at
                  FROM seller_quality_scores WHERE seller_id = ?
                """, sellerId);
            return Optional.of(new SellerQualityView(
                ((Number) row.get("score")).doubleValue(),
                Tier.valueOf((String) row.get("tier")),
                new JSONObject(row.get("components").toString()).toMap(),
                ((Number) row.get("order_count")).intValue(),
                (Boolean) row.get("is_provisional")));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public record CurrentScore(double score, Tier tier) { }

    public record SellerQualityView(double score, Tier tier, Map<String, Object> components,
                                     int orderCount, boolean isProvisional) { }
}
