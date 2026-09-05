package com.verdant.finance.service;

import com.verdant.finance.domain.BreakType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.UUID;

@Repository
public class ReconciliationBreakRepository {

    private final JdbcTemplate jdbc;

    public ReconciliationBreakRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void record(UUID runId, BreakType type, String providerRef, UUID orderId,
                        BigDecimal providerAmount, BigDecimal internalAmount) {
        jdbc.update("""
            INSERT INTO reconciliation_breaks (run_id, break_type, provider_ref, order_id, provider_amount, internal_amount)
            VALUES (?, ?, ?, ?, ?, ?)
            """, runId, type.name(), providerRef, orderId, providerAmount, internalAmount);
    }

    public int countUnresolvedForRun(UUID runId) {
        Integer count = jdbc.queryForObject(
            "SELECT count(*) FROM reconciliation_breaks WHERE run_id = ? AND resolved_at IS NULL", Integer.class, runId);
        return count == null ? 0 : count;
    }
}
