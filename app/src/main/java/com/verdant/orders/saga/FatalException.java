package com.verdant.orders.saga;

/** A step failed in a way retrying can never fix (e.g. the order expired without payment) - go straight to compensation. */
public class FatalException extends RuntimeException {
    public FatalException(String message) { super(message); }
    public FatalException(String message, Throwable cause) { super(message, cause); }
}
