package com.verdant.notifications.gateway;

public class RateLimitedException extends Exception {
    public RateLimitedException(String message) {
        super(message);
    }
}
