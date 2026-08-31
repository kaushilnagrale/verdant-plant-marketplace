package com.verdant.common.domain;

import org.junit.jupiter.api.Test;
import java.util.Currency;
import static org.assertj.core.api.Assertions.*;

class MoneyTest {

    @Test void scaleIsAlwaysTwo() {
        assertThat(Money.of("10.1").amount().scale()).isEqualTo(2);
    }

    @Test void additionIsExact_unlikeDouble() {
        assertThat(Money.of("0.10").plus(Money.of("0.20")))
            .isEqualTo(Money.of("0.30"));
    }

    @Test void currencyMismatch_isRejected() {
        Money usd = new Money(new java.math.BigDecimal("1"), Currency.getInstance("USD"));
        assertThatThrownBy(() -> Money.of("1").plus(usd))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
