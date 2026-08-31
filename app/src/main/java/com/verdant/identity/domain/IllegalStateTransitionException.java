package com.verdant.identity.domain;

public class IllegalStateTransitionException extends RuntimeException {
    public IllegalStateTransitionException(SellerStatus from, SellerStatus to) {
        super("Illegal seller transition: " + from + " -> " + to);
    }
}
