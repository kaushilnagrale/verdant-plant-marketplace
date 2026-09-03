package com.verdant.orders.saga;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/** Composite key (order_id, step_name) - plain JDBC over JPA ceremony, same reasoning as BalanceSnapshotRepository. */
@Repository
public class SagaCompletedStepRepository {

    private final JdbcTemplate jdbc;

    public SagaCompletedStepRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean hasCompleted(UUID orderId, String stepName) {
        Integer count = jdbc.queryForObject(
            "SELECT count(*) FROM saga_completed_steps WHERE order_id = ? AND step_name = ?",
            Integer.class, orderId, stepName);
        return count != null && count > 0;
    }

    public void markCompleted(UUID orderId, String stepName) {
        jdbc.update("""
            INSERT INTO saga_completed_steps (order_id, step_name) VALUES (?, ?)
            ON CONFLICT (order_id, step_name) DO NOTHING
            """, orderId, stepName);
    }
}
