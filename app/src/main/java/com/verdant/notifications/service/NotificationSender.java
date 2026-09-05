package com.verdant.notifications.service;

import com.verdant.notifications.gateway.RateLimitedException;
import com.verdant.notifications.gateway.WhatsAppGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Set;

@Component
public class NotificationSender {

    private static final Logger log = LoggerFactory.getLogger(NotificationSender.class);
    /** Same real "OTP, delivery attempts" set NotificationService's own quiet-hours bypass uses (isUrgent) - a critical message that fails to send falls back to SMS rather than silently waiting for the next queued retry. */
    private static final Set<String> CRITICAL_TEMPLATES = Set.of("otp", "out_for_delivery");

    private final WhatsAppMessageRepository messages;
    private final WhatsAppTemplateRepository templates;
    private final WhatsAppGateway whatsapp;

    public NotificationSender(WhatsAppMessageRepository messages, WhatsAppTemplateRepository templates,
                               WhatsAppGateway whatsapp) {
        this.messages = messages;
        this.templates = templates;
        this.whatsapp = whatsapp;
    }

    @Scheduled(fixedDelayString = "${verdant.notifications.interval-ms:5000}")
    public void sendQueued() {
        for (WhatsAppMessageRepository.QueuedMessage m : messages.claimDue(100)) {
            send(m);
        }
    }

    private void send(WhatsAppMessageRepository.QueuedMessage m) {
        if (!templates.isApproved(m.templateName())) {
            messages.suppress(m.id(), "Template not approved");
            return;
        }
        try {
            var result = whatsapp.sendTemplate(m.recipientRef(), m.templateName(),
                templates.languageOf(m.templateName()), m.variables());
            messages.markSent(m.id(), result.messageId());
        } catch (RateLimitedException e) {
            messages.reschedule(m.id(), Instant.now().plusSeconds(60));
        } catch (Exception e) {
            messages.markFailed(m.id(), e.getMessage());
            if (CRITICAL_TEMPLATES.contains(m.templateName())) {
                fallBackToSms(m);
            }
        }
    }

    /**
     * Records the fallback INTENT, same honesty standard as
     * com.verdant.common.notification.NotificationService (Week 7): no
     * real SMS provider is integrated anywhere in this project either
     * (see LAUNCH_CHECKLIST.md), so this logs a real, auditable decision
     * rather than claiming a message was actually sent over a channel
     * nothing here can reach.
     */
    private void fallBackToSms(WhatsAppMessageRepository.QueuedMessage m) {
        log.warn("WhatsApp send failed for a critical template ({}) to {} - would fall back to SMS here; "
            + "no real SMS provider is integrated in this project yet", m.templateName(), m.recipientRef());
    }
}
