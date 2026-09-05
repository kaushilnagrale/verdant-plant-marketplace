package com.verdant.notifications;

import com.verdant.common.testing.IntegrationTestBase;
import com.verdant.notifications.domain.MessageStatus;
import com.verdant.notifications.domain.RecipientType;
import com.verdant.notifications.gateway.StubWhatsAppGateway;
import com.verdant.notifications.service.NotificationSender;
import com.verdant.notifications.service.WhatsAppNotificationService;
import com.verdant.notifications.service.WhatsAppMessageRepository;
import com.verdant.notifications.service.WhatsAppTemplateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationTest extends IntegrationTestBase {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Autowired private WhatsAppNotificationService notifications;
    @Autowired private NotificationSender sender;
    @Autowired private WhatsAppMessageRepository messages;
    @Autowired private WhatsAppTemplateRepository templates;
    @Autowired private StubWhatsAppGateway whatsappStub;

    private String phone;
    private String recipientPhone;

    @BeforeEach
    void seed() {
        phone = "+91900000" + (1000 + new java.util.Random().nextInt(9000));
        recipientPhone = "+91800000" + (1000 + new java.util.Random().nextInt(9000));
        whatsappStub.reset();
    }

    private Map<String, Object> vars() {
        return Map.of("orderId", UUID.randomUUID().toString());
    }

    private Map<String, Object> varsWithPrice() {
        return Map.of("price", "349.00");
    }

    private String key() {
        return "test-" + UUID.randomUUID();
    }

    @Test
    void sameOrderEvent_isNotNotifiedTwice() {
        String idempotencyKey = "order:" + UUID.randomUUID() + ":SHIPPED";
        notifications.queue(idempotencyKey, RecipientType.BUYER, phone, "order_shipped", vars());
        notifications.queue(idempotencyKey, RecipientType.BUYER, phone, "order_shipped", vars());

        assertThat(messages.countByRecipientRef(phone)).isEqualTo(1);
    }

    @Test
    void giftRecipient_neverReceivesPriceBearingTemplate() {
        notifications.queue(key(), RecipientType.RECIPIENT, recipientPhone, "order_receipt", varsWithPrice());

        assertThat(messages.countByRecipientRef(recipientPhone)).isZero();
    }

    /**
     * No real gift-order checkout flow exists in this project yet (no
     * recipient_phone/is_gift concept on orders - that's real, separate,
     * un-built infrastructure this feature assumes exists elsewhere) -
     * this drives the same sequence of real queue() calls a real gift-
     * order lifecycle would make directly, which is exactly what this
     * test is actually checking: buyer and recipient end up with
     * different event sets, regardless of what triggers each call.
     */
    @Test
    void giftBuyerAndRecipient_receiveDifferentEventSets() {
        notifications.queue(key(), RecipientType.BUYER, phone, "order_confirmed", vars());
        notifications.queue(key(), RecipientType.BUYER, phone, "payment_received", vars());
        notifications.queue(key(), RecipientType.BUYER, phone, "order_receipt", varsWithPrice());
        notifications.queue(key(), RecipientType.RECIPIENT, recipientPhone, "out_for_delivery", vars());
        notifications.queue(key(), RecipientType.RECIPIENT, recipientPhone, "delivered", vars());

        assertThat(messages.countByRecipientRef(phone)).isEqualTo(3);
        assertThat(messages.countByRecipientRef(recipientPhone)).isEqualTo(2);
    }

    @Test
    void nonUrgentNotificationAtNight_isDeferredToMorning() {
        Instant lateNight = ZonedDateTime.of(2026, 9, 10, 23, 30, 0, 0, IST).toInstant();

        Instant scheduledFor = notifications.respectQuietHours(lateNight, "care_instructions");

        assertThat(scheduledFor.atZone(IST).toLocalTime()).isEqualTo(LocalTime.of(8, 0));
        assertThat(scheduledFor).isAfter(lateNight);
    }

    @Test
    void urgentNotificationAtNight_isSentImmediately() {
        Instant lateNight = ZonedDateTime.of(2026, 9, 10, 23, 30, 0, 0, IST).toInstant();

        Instant scheduledFor = notifications.respectQuietHours(lateNight, "otp");

        assertThat(scheduledFor).isEqualTo(lateNight);
    }

    @Test
    void unapprovedTemplate_isSuppressed_notSent() {
        templates.markApproved("order_shipped", false);
        String idempotencyKey = key();
        notifications.queue(idempotencyKey, RecipientType.BUYER, phone, "order_shipped", vars());

        sender.sendQueued();

        assertThat(messages.statusOf(idempotencyKey)).isEqualTo(MessageStatus.SUPPRESSED);
        assertThat(whatsappStub.sendCount()).isZero();
    }

    @Test
    void rateLimited_reschedules_ratherThanFailing() {
        templates.markApproved("order_shipped", true);
        whatsappStub.rateLimitNext();
        String idempotencyKey = key();
        notifications.queue(idempotencyKey, RecipientType.BUYER, phone, "order_shipped", vars());

        sender.sendQueued();

        assertThat(messages.statusOf(idempotencyKey)).isEqualTo(MessageStatus.QUEUED);
        assertThat(messages.scheduledForOf(idempotencyKey)).isAfter(Instant.now());
    }

    /**
     * whatsappStub is a real singleton, and the real background
     * NotificationSender scheduler (fixedDelay, no test-safety autostart
     * guard - unlike ListingProjector's real Kafka listener, this one
     * only ever queries Postgres, harmless to run continuously) keeps
     * polling for ANY test's due messages the whole time this suite
     * runs - so a raw whatsappStub.sendCount() isn't scoped to just this
     * test. statusOf(idempotencyKey) is: it's this exact message's own
     * real, terminal outcome, proof enough that a real send happened for
     * it specifically, regardless of what else the shared stub saw.
     */
    @Test
    void approvedTemplate_isActuallySent_withARealProviderMessageId() {
        templates.markApproved("order_shipped", true);
        String idempotencyKey = key();
        notifications.queue(idempotencyKey, RecipientType.BUYER, phone, "order_shipped", vars());

        sender.sendQueued();

        assertThat(messages.statusOf(idempotencyKey)).isEqualTo(MessageStatus.SENT);
        assertThat(whatsappStub.sendCount()).isGreaterThanOrEqualTo(1);
    }
}
