package com.verdant.payment;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "razorpay")
public record RazorpayProperties(String keyId, String keySecret, String webhookSecret) { }
