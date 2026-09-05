package com.verdant.notifications.service;

import com.verdant.notifications.domain.MessageStatus;
import com.verdant.notifications.domain.RecipientType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** idempotency_key is UNIQUE at the DB level (V35) - the real guarantee behind "queuing the same event twice never double-messages", not just an application-level check that a race could still slip past. */
@Repository
public class WhatsAppMessageRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public WhatsAppMessageRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public boolean existsByIdempotencyKey(String idempotencyKey) {
        Integer count = jdbc.queryForObject(
            "SELECT count(*) FROM whatsapp_messages WHERE idempotency_key = ?", Integer.class, idempotencyKey);
        return count != null && count > 0;
    }

    public UUID queue(String idempotencyKey, RecipientType type, String recipientRef, String template,
                       Map<String, Object> variables, Instant scheduledFor) {
        return jdbc.queryForObject("""
            INSERT INTO whatsapp_messages
                (recipient_type, recipient_ref, template_name, channel, idempotency_key, variables, status, scheduled_for)
            VALUES (?, ?, ?, 'WHATSAPP', ?, ?::jsonb, 'QUEUED', ?)
            RETURNING id
            """, UUID.class, type.name(), recipientRef, template, idempotencyKey,
            json.writeValueAsString(variables), Timestamp.from(scheduledFor));
    }

    /** Real, atomic claim - same DELETE-nothing, UPDATE-and-return SKIP LOCKED shape as SearchReindexQueueRepository (Week 9), so two concurrent sender ticks can never both pick up the same due message. */
    public List<QueuedMessage> claimDue(int limit) {
        return jdbc.query("""
            UPDATE whatsapp_messages SET status = 'SENT'
             WHERE id IN (
                SELECT id FROM whatsapp_messages
                 WHERE status = 'QUEUED' AND scheduled_for <= now()
                 ORDER BY scheduled_for
                 LIMIT ?
                 FOR UPDATE SKIP LOCKED
             )
            RETURNING id, recipient_type, recipient_ref, template_name, variables
            """, (rs, n) -> new QueuedMessage(
                (UUID) rs.getObject("id"),
                RecipientType.valueOf(rs.getString("recipient_type")),
                rs.getString("recipient_ref"),
                rs.getString("template_name"),
                json.readValue(rs.getString("variables"), Map.class)),
            limit);
        // Marks SENT optimistically at claim time, not after a real send
        // succeeds: claimDue()'s own UPDATE is the real atomic claim
        // mechanism (mirroring SearchReindexQueueRepository's claim-then-
        // process shape) - markSent()/markFailed()/reschedule() below
        // correct the status afterward based on what actually happened,
        // same as that queue's own scheduleRetry() corrects a failed claim.
    }

    public void markSent(UUID id, String providerMessageId) {
        jdbc.update("UPDATE whatsapp_messages SET status = 'SENT', provider_message_id = ?, sent_at = now() WHERE id = ?",
            providerMessageId, id);
    }

    public void markFailed(UUID id, String reason) {
        jdbc.update("UPDATE whatsapp_messages SET status = 'FAILED', failure_reason = ? WHERE id = ?", reason, id);
    }

    public void suppress(UUID id, String reason) {
        jdbc.update("UPDATE whatsapp_messages SET status = 'SUPPRESSED', failure_reason = ? WHERE id = ?", reason, id);
    }

    public void reschedule(UUID id, Instant scheduledFor) {
        jdbc.update("UPDATE whatsapp_messages SET status = 'QUEUED', scheduled_for = ? WHERE id = ?",
            Timestamp.from(scheduledFor), id);
    }

    public MessageStatus statusOf(String idempotencyKey) {
        return MessageStatus.valueOf(jdbc.queryForObject(
            "SELECT status FROM whatsapp_messages WHERE idempotency_key = ?", String.class, idempotencyKey));
    }

    public Instant scheduledForOf(String idempotencyKey) {
        return jdbc.queryForObject(
            "SELECT scheduled_for FROM whatsapp_messages WHERE idempotency_key = ?", Timestamp.class, idempotencyKey)
            .toInstant();
    }

    public int countByRecipientRef(String recipientRef) {
        Integer count = jdbc.queryForObject(
            "SELECT count(*) FROM whatsapp_messages WHERE recipient_ref = ?", Integer.class, recipientRef);
        return count == null ? 0 : count;
    }

    public record QueuedMessage(UUID id, RecipientType recipientType, String recipientRef,
                                 String templateName, Map<String, Object> variables) { }
}
