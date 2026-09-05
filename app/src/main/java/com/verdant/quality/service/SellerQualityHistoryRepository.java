package com.verdant.quality.service;

import com.verdant.quality.domain.Tier;
import org.json.JSONObject;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Append-only - every recompute leaves a permanent row here, even when the score didn't change, so a seller (or ops) can see the actual computation history, not just the current snapshot. */
@Repository
public class SellerQualityHistoryRepository {

    private final JdbcTemplate jdbc;

    public SellerQualityHistoryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void record(UUID sellerId, double score, Tier tier, Map<String, Object> componentsJson) {
        jdbc.update("""
            INSERT INTO seller_quality_history (seller_id, score, tier, components)
            VALUES (?, ?, ?, ?::jsonb)
            """, sellerId, score, tier.name(), new JSONObject(componentsJson).toString());
    }

    /** id is BIGSERIAL (a bigint), not a UUID - matches the real seller_quality_history schema (V34). */
    public List<Long> findBySellerId(UUID sellerId) {
        return jdbc.queryForList("SELECT id FROM seller_quality_history WHERE seller_id = ?", Long.class, sellerId);
    }
}
