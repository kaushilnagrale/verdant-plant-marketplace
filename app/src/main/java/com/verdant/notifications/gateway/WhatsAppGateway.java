package com.verdant.notifications.gateway;

import java.util.Map;

public interface WhatsAppGateway {

    SendResult sendTemplate(String recipientPhone, String templateName, String language, Map<String, Object> variables)
        throws RateLimitedException;

    record SendResult(String messageId) { }
}
