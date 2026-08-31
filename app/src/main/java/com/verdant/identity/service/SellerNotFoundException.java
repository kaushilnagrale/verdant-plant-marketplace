package com.verdant.identity.service;

public class SellerNotFoundException extends RuntimeException {
    public SellerNotFoundException() {
        super("Seller not found");
    }
}
