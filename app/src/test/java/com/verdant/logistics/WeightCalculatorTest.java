package com.verdant.logistics;

import com.verdant.logistics.domain.WeightCalculator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.assertj.core.api.Assertions.assertThat;

class WeightCalculatorTest {

    private final WeightCalculator calc = new WeightCalculator();

    @Test
    void bulkyLightPlant_billsOnVolumetricNotDeadWeight() {
        var w = calc.calculate(30, 30, 50, 1200);      // 30x30x50cm pot, 1.2kg actual
        assertThat(w.volumetricGrams()).isEqualTo(9000);
        assertThat(w.billableGrams()).isEqualTo(9000); // not 1200
    }

    @ParameterizedTest
    @CsvSource({ "30,30,50,1200,9000", "20,20,25,800,2000", "15,15,20,2000,2000" })
    void billableIsAlwaysTheHigher(int l, int w, int h, int dead, int expected) {
        assertThat(calc.calculate(l, w, h, dead).billableGrams()).isEqualTo(expected);
    }
}
