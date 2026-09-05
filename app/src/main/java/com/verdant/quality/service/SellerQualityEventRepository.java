package com.verdant.quality.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/** A pure audit log - nothing here ever gets updated or deleted, only appended to and later aggregated by QualityStatsRepository. */
@Repository
public class SellerQualityEventRepository {

    private final JdbcTemplate jdbc;

    public SellerQualityEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void record(UUID sellerId, String eventType, UUID orderId) {
        jdbc.update("""
            INSERT INTO seller_quality_events (seller_id, event_type, order_id) VALUES (?, ?, ?)
            """, sellerId, eventType, orderId);
    }
}
