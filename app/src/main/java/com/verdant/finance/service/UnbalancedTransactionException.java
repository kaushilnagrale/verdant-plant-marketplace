package com.verdant.finance.service;

import com.verdant.common.domain.Money;

public class UnbalancedTransactionException extends RuntimeException {
    public UnbalancedTransactionException(Money sum) {
        super("Ledger transaction does not balance: sum = " + sum.amount());
    }
}
