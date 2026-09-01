package com.verdant.catalog.mapping;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class RrfTest {

    @Test
    void itemRankedHighlyByBothRetrievers_winsOverItemRankedFirstByOnlyOne() {
        UUID both = UUID.randomUUID();
        UUID onlyVector = UUID.randomUUID();
        UUID onlyLexical = UUID.randomUUID();

        var fused = HybridRetriever.fuse(
            List.of(onlyVector, both),
            List.of(onlyLexical, both));

        assertThat(fused.get(0).canonicalProductId()).isEqualTo(both);
    }

    @Test
    void emptyResultFromOneRetriever_stillProducesRanking() {
        UUID a = UUID.randomUUID();
        assertThat(HybridRetriever.fuse(List.of(a), List.of())).hasSize(1);
    }
}
