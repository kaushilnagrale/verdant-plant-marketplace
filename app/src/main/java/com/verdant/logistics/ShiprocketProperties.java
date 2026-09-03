package com.verdant.logistics;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * webhookApiKey is a shared secret you configure once in Shiprocket's own
 * dashboard, sent back as a static X-Api-Key header on every tracking
 * webhook call - unlike Razorpay, Shiprocket doesn't compute an HMAC
 * signature over the body, so verification here is a constant-time string
 * comparison against this configured value, not signature math.
 */
@ConfigurationProperties(prefix = "shiprocket")
public record ShiprocketProperties(String email, String password, String webhookApiKey) { }
