package com.verdant.finance.service;

import com.verdant.common.domain.Money;

/** The failure that would actually hurt - never post a payout the ledger doesn't back. */
public class InsufficientPayableException extends RuntimeException {
    public InsufficientPayableException(Money requested, Money available) {
        super("Payout of " + requested + " exceeds available payable balance of " + available);
    }
}
