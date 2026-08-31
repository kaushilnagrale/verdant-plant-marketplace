package com.verdant.identity.service;

public interface BankVerificationGateway {
    /** Deposits a nominal amount and returns the reference the seller must confirm. */
    String initiatePennyDrop(String accountNumber, String ifsc);
}
