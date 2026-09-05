package com.verdant.notifications.gateway;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Stub only, no @Profile restriction - active in every profile including
 * prod, same treatment as StubPayoutGateway (Week 8): no real WhatsApp
 * Business API account/credentials exist anywhere in this project (the
 * runbook's own prerequisite - "WhatsApp Business templates should
 * already be submitted... it takes days" - is a real external business
 * action item this session cannot complete on its own, the same class of
 * gap as the Razorpay KYC block and the never-obtained real payout
 * gateway). A real integration would replace this with a real Meta
 * Cloud API (or a BSP like Gupshup/Twilio) client, keeping the same
 * WhatsAppGateway contract.
 */
@Component
public class StubWhatsAppGateway implements WhatsAppGateway {

    private final AtomicInteger sendCount = new AtomicInteger(0);
    private final AtomicBoolean rateLimitNext = new AtomicBoolean(false);
    private final Map<String, Integer> sendsByRecipient = new ConcurrentHashMap<>();

    @Override
    public SendResult sendTemplate(String recipientPhone, String templateName, String language,
                                    Map<String, Object> variables) throws RateLimitedException {
        sendCount.incrementAndGet();
        sendsByRecipient.merge(recipientPhone, 1, Integer::sum);
        if (rateLimitNext.compareAndSet(true, false)) {
            throw new RateLimitedException("Simulated rate limit for " + recipientPhone);
        }
        return new SendResult("wamid.stub." + java.util.UUID.randomUUID());
    }

    public void rateLimitNext() {
        rateLimitNext.set(true);
    }

    public int sendCount() {
        return sendCount.get();
    }

    public void reset() {
        sendCount.set(0);
        rateLimitNext.set(false);
        sendsByRecipient.clear();
    }
}
