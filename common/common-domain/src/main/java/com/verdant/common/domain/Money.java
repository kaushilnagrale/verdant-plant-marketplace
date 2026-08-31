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

    private void require(Money o) {
        if (!currency.equals(o.currency))
            throw new IllegalArgumentException("Currency mismatch: " + currency + " vs " + o.currency);
    }
}
