package com.verdant.orders.saga;

/** A step failed but might succeed if tried again later (e.g. still waiting on payment, a transient gateway error). */
public class RetryableException extends RuntimeException {
    public RetryableException(String message) { super(message); }
    public RetryableException(String message, Throwable cause) { super(message, cause); }
}
