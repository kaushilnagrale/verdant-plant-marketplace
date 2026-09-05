package com.verdant.finance.service;

import com.verdant.finance.domain.ReconciliationRun;
import com.verdant.finance.domain.ReconciliationStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.UUID;

@Repository
public class ReconciliationRunRepository {

    private final JdbcTemplate jdbc;

    public ReconciliationRunRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * run_date is UNIQUE, and a rerun for the same date must be genuinely
     * idempotent (Day 46's own named test: reconciliationRerun_forSameDate_
     * isIdempotent) - not "fails with a duplicate-key error" and not
     * "silently accumulates a second, conflicting result". ON CONFLICT DO
     * UPDATE resets the SAME row back to RUNNING with fresh zeroed counts,
     * and the break rows from any earlier attempt at this date are cleared
     * in the same transaction, so a rerun always recomputes cleanly from
     * scratch rather than mixing stale and fresh breaks together.
     */
    @Transactional
    public UUID start(LocalDate date) {
        UUID id = jdbc.queryForObject("""
            INSERT INTO reconciliation_runs (run_date, status, started_at, completed_at,
                                              provider_count, internal_count, matched, breaks_found)
            VALUES (?, 'RUNNING', now(), NULL, 0, 0, 0, 0)
            ON CONFLICT (run_date) DO UPDATE
                SET status = 'RUNNING', started_at = now(), completed_at = NULL,
                    provider_count = 0, internal_count = 0, matched = 0, breaks_found = 0
            RETURNING id
            """, UUID.class, date);
        jdbc.update("DELETE FROM reconciliation_breaks WHERE run_id = ?", id);
        return id;
    }

    public void complete(UUID runId, int providerCount, int internalCount, int matched, int breaksFound) {
        ReconciliationStatus status = breaksFound > 0 ? ReconciliationStatus.BREAKS : ReconciliationStatus.CLEAN;
        jdbc.update("""
            UPDATE reconciliation_runs
               SET status = ?, provider_count = ?, internal_count = ?, matched = ?, breaks_found = ?, completed_at = now()
             WHERE id = ?
            """, status.name(), providerCount, internalCount, matched, breaksFound, runId);
    }

    public void markFailed(UUID runId) {
        jdbc.update("UPDATE reconciliation_runs SET status = 'FAILED', completed_at = now() WHERE id = ?", runId);
    }

    public ReconciliationRun findByDate(LocalDate date) {
        var row = jdbc.queryForMap("""
            SELECT id, run_date, status, provider_count, internal_count, matched, breaks_found, started_at, completed_at
              FROM reconciliation_runs WHERE run_date = ?
            """, date);
        Timestamp completedAt = (Timestamp) row.get("completed_at");
        return new ReconciliationRun(
            (UUID) row.get("id"),
            ((java.sql.Date) row.get("run_date")).toLocalDate(),
            ReconciliationStatus.valueOf((String) row.get("status")),
            (Integer) row.get("provider_count"),
            (Integer) row.get("internal_count"),
            (Integer) row.get("matched"),
            (Integer) row.get("breaks_found"),
            ((Timestamp) row.get("started_at")).toInstant(),
            completedAt == null ? null : completedAt.toInstant());
    }
}
