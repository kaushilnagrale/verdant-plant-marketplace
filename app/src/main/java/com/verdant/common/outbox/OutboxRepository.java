package com.verdant.common.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OutboxRepository extends JpaRepository<OutboxEvent, Long> {

    List<OutboxEvent> findByAggregateId(UUID aggregateId);

    /**
     * FOR UPDATE SKIP LOCKED is the standard way to build a queue on
     * Postgres: multiple drainer instances can call this concurrently
     * without ever claiming the same row, no coordination needed beyond
     * the database itself. Not expressible via JPQL, hence native.
     */
    @Query(value = """
        SELECT * FROM outbox
         WHERE published_at IS NULL AND next_attempt_at <= now()
         ORDER BY id
         LIMIT :limit
         FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    List<OutboxEvent> claimBatch(@Param("limit") int limit);

    @Modifying
    @Query("UPDATE OutboxEvent e SET e.publishedAt = :publishedAt WHERE e.id = :id")
    void markPublished(@Param("id") Long id, @Param("publishedAt") Instant publishedAt);

    @Modifying
    @Query("UPDATE OutboxEvent e SET e.nextAttemptAt = :nextAttempt, e.lastError = :error, e.attempts = e.attempts + 1 WHERE e.id = :id")
    void scheduleRetry(@Param("id") Long id, @Param("nextAttempt") Instant nextAttempt, @Param("error") String error);

    @Modifying
    @Query("UPDATE OutboxEvent e SET e.nextAttemptAt = :neverAgain, e.lastError = :error, e.attempts = e.attempts + 1 WHERE e.id = :id")
    void markDeadInternal(@Param("id") Long id, @Param("error") String error, @Param("neverAgain") Instant neverAgain);

    /** Retries are permanently exhausted - push next_attempt_at far enough out it is never claimed again, but leave the row (published_at still NULL) for inspection/alerting. */
    default void markDead(Long id, String error) {
        markDeadInternal(id, error, Instant.parse("9999-12-31T23:59:59Z"));
    }
}
