package com.verdant.inventory;

import com.verdant.inventory.domain.InventoryPolicy;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.assertj.core.api.Assertions.assertThat;

class InventoryPolicyTest {

    @ParameterizedTest
    @CsvSource({ "10,1,9", "2,1,1", "1,1,0", "0,1,0", "5,0,5" })
    void sellableHoldsBackBuffer(int qty, int buffer, int expected) {
        assertThat(InventoryPolicy.sellable(qty, buffer)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({ "10,1,false", "2,1,false", "1,1,true", "0,1,true" })
    void autoPausesAtOrBelowBuffer(int qty, int buffer, boolean expected) {
        assertThat(InventoryPolicy.shouldAutoPause(qty, buffer)).isEqualTo(expected);
    }
}
