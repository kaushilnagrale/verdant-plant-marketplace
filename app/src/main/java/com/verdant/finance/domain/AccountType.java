package com.verdant.finance.domain;

public enum AccountType {
    BUYER_RECEIVABLE,      // what buyers owe or have paid
    SELLER_PAYABLE,        // what the platform owes a seller
    COMMISSION_REVENUE,    // platform income
    TAX_WITHHELD,          // TCS/TDS held for remittance
    GATEWAY_FEE_EXPENSE,   // Razorpay fees
    PLATFORM_CASH,         // money actually held
    CLAIMS_EXPENSE,        // platform-absorbed claims
    REFUNDS_PAYABLE;

    public boolean isPerOwner() {
        return this == SELLER_PAYABLE || this == BUYER_RECEIVABLE;
    }
}
