package com.verdant.common.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

public record Money(BigDecimal amount, Currency currency) {

    public static final Currency INR = Currency.getInstance("INR");
    public static final Money ZERO = of(BigDecimal.ZERO);

    public Money {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        amount = amount.setScale(2, RoundingMode.HALF_UP);
    }

    public static Money of(BigDecimal amount) { return new Money(amount, INR); }
    public static Money of(String amount)     { return of(new BigDecimal(amount)); }

    public Money plus(Money o)  { require(o); return new Money(amount.add(o.amount), currency); }
    public Money minus(Money o) { require(o); return new Money(amount.subtract(o.amount), currency); }
    public Money negate()       { return new Money(amount.negate(), currency); }
    public boolean isZero()     { return amount.signum() == 0; }

    /** Line-item total: unit price times a quantity (e.g. cart pricing). */
    public Money times(int quantity) {
        return new Money(amount.multiply(BigDecimal.valueOf(quantity)), currency);
    }

    /**
     * A percentage of this amount (e.g. commission = gross.multiply(0.12)).
     * Rounds to 2dp HALF_UP via the compact constructor - deliberately not
     * exact, which is why settlement derives the final leg by subtraction
     * rather than its own percentage (see SettlementService).
     */
    public Money multiply(BigDecimal rate) {
        return new Money(amount.multiply(rate), currency);
    }

    private void require(Money o) {
        if (!currency.equals(o.currency))
            throw new IllegalArgumentException("Currency mismatch: " + currency + " vs " + o.currency);
    }
}
