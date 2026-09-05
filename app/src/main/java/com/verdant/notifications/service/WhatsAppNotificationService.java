package com.verdant.notifications.service;

import com.verdant.notifications.domain.RecipientType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Named WhatsAppNotificationService, not the runbook's own bare
 * "NotificationService" - com.verdant.common.notification.NotificationService
 * (Week 7) already exists, and Spring's default component-scan bean
 * naming (the simple class name) collided the moment both were on the
 * classpath at once (a real ConflictingBeanDefinitionException, not a
 * hypothetical one). They're genuinely different systems (see this
 * package's own migration comment - Week 7's is an internal ops/seller
 * "record of intent" log, this is a real outbound buyer/recipient
 * delivery queue with idempotency, quiet hours, and gift routing), so
 * this rename also makes that real distinction visible in code, not just
 * in a comment.
 *
 * The gift-suppression check is a hard guard, not a convention (55.2's
 * own emphasis): it catches the case where someone later adds a template
 * containing a price and forgets recipients exist at all. idempotencyKey
 * is derived from the real triggering event by every real caller (e.g.
 * "order:{id}:SHIPPED") - a retry of the same event can never double
 * message a customer, enforced twice over (the UNIQUE constraint on
 * idempotency_key is the real guarantee; the existsByIdempotencyKey()
 * check below just avoids a wasted round trip on the common case).
 */
@Service
public class WhatsAppNotificationService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime QUIET_START = LocalTime.of(22, 0);
    private static final LocalTime QUIET_END = LocalTime.of(8, 0);

    private final WhatsAppMessageRepository messages;
    private final WhatsAppTemplateRepository templates;

    public WhatsAppNotificationService(WhatsAppMessageRepository messages, WhatsAppTemplateRepository templates) {
        this.messages = messages;
        this.templates = templates;
    }

    @Transactional
    public void queue(String idempotencyKey, RecipientType type, String recipientRef,
                       String template, Map<String, Object> variables) {
        if (messages.existsByIdempotencyKey(idempotencyKey)) {
            return; // already queued - a retry of the same event
        }

        // Gift orders: price-bearing templates never reach the recipient,
        // only the buyer. Checked before anything else - a template
        // someone adds later with a price in it is caught here
        // automatically, not by every caller remembering to check.
        if (type == RecipientType.RECIPIENT && templates.isPriceBearing(template)) {
            return;
        }

        Instant sendAt = respectQuietHours(Instant.now(), template);
        messages.queue(idempotencyKey, type, recipientRef, template, variables, sendAt);
    }

    /**
     * Public, not private: lets a test drive this real deferral math
     * directly with an explicit Instant, same idea as this project's
     * usual "backdate the stored timestamp" trick elsewhere
     * (OrderExpiryServiceTest et al.) - there's no stored row to backdate
     * here since this computation happens BEFORE anything is persisted,
     * so an explicit instant is the equivalent for this one case rather
     * than introducing a new Clock-injection pattern nothing else in this
     * project uses. queue() itself still calls Instant.now() for real use.
     */
    public Instant respectQuietHours(Instant now, String template) {
        if (templates.isUrgent(template)) {
            return now; // OTP, delivery attempts - never deferred
        }

        ZonedDateTime ist = now.atZone(IST);
        LocalTime t = ist.toLocalTime();

        if (t.isAfter(QUIET_START) || t.isBefore(QUIET_END)) {
            // Already past 22:00 tonight -> defer to 08:00 tomorrow.
            // Already past midnight but before 08:00 -> defer to 08:00 TODAY.
            boolean pastMidnightBeforeQuietEnd = t.isBefore(QUIET_END);
            return ist.toLocalDate().plusDays(pastMidnightBeforeQuietEnd ? 0 : 1)
                .atTime(QUIET_END).atZone(IST).toInstant();
        }
        return now;
    }
}
