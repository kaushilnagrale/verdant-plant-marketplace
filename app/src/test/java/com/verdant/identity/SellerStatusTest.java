package com.verdant.identity;

import com.verdant.identity.domain.IllegalStateTransitionException;
import com.verdant.identity.domain.SellerStatus;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Stream;

import static com.verdant.identity.domain.SellerStatus.*;
import static org.assertj.core.api.Assertions.*;

class SellerStatusTest {

    private static final Set<String> LEGAL = Set.of(
        "DRAFT->PENDING_REVIEW", "DRAFT->OFFBOARDED",
        "PENDING_REVIEW->APPROVED", "PENDING_REVIEW->DRAFT", "PENDING_REVIEW->OFFBOARDED",
        "APPROVED->SUSPENDED", "APPROVED->OFFBOARDED",
        "SUSPENDED->APPROVED", "SUSPENDED->OFFBOARDED"
    );

    static Stream<Object[]> allPairs() {
        return Arrays.stream(values())
            .flatMap(a -> Arrays.stream(values()).map(b -> new Object[]{a, b}));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("allPairs")
    void transitionMatrix_matchesSpecification(SellerStatus from, SellerStatus to) {
        boolean expected = LEGAL.contains(from + "->" + to);
        assertThat(from.canMoveTo(to))
            .as("%s -> %s", from, to)
            .isEqualTo(expected);
    }

    @Test void offboarded_isTerminal() {
        Arrays.stream(values()).forEach(s ->
            assertThatThrownBy(() -> OFFBOARDED.assertCanMoveTo(s))
                .isInstanceOf(IllegalStateTransitionException.class));
    }

    @Test void suspendedSeller_canBeReinstated() {
        assertThatCode(() -> SUSPENDED.assertCanMoveTo(APPROVED)).doesNotThrowAnyException();
    }
}
