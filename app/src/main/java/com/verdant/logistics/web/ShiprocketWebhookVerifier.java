package com.verdant.logistics.web;

import com.verdant.logistics.ShiprocketProperties;
import org.springframework.stereotype.Component;

/** Constant-time comparison - see ShiprocketProperties for why this isn't HMAC verification. */
@Component
public class ShiprocketWebhookVerifier {

    private final ShiprocketProperties props;

    public ShiprocketWebhookVerifier(ShiprocketProperties props) {
        this.props = props;
    }

    public boolean isValid(String apiKey) {
        String expected = props.webhookApiKey();
        if (expected == null || apiKey == null) {
            return false;
        }
        return java.security.MessageDigest.isEqual(
            expected.getBytes(java.nio.charset.StandardCharsets.UTF_8),
            apiKey.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
