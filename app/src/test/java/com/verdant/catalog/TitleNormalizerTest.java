package com.verdant.catalog;

import com.verdant.catalog.mapping.TitleNormalizer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class TitleNormalizerTest {

    private final TitleNormalizer n = new TitleNormalizer();

    @ParameterizedTest
    @CsvSource({
        "'MONEY PLANT (FREE DELIVERY!!!)', 'money plant'",
        "'Money Plant 6 inch pot',         'money plant pot'",
        "'  money   plant  ',              'money plant'",
        "'Best Quality Money Plant',       'money plant'"
    })
    void stripsNoiseAndNormalizesWhitespace(String raw, String expected) {
        assertThat(n.normalize(raw).text()).isEqualTo(expected);
    }

    @Test void transliteratesDevanagari() {
        assertThat(n.normalize("मनी प्लांट").text()).contains("man");
    }

    @Test void extractsSizeAndRemovesItFromText() {
        var r = n.normalize("Snake Plant 12 inch");
        assertThat(r.sizeInches()).isEqualTo(12);
        assertThat(r.text()).doesNotContain("12");
    }

    @Test void convertsCentimetresToInches() {
        assertThat(n.normalize("Tulsi 30 cm").sizeInches()).isEqualTo(12);
    }

    @Test void handlesNullAndBlank() {
        assertThat(n.normalize(null).text()).isEmpty();
        assertThat(n.normalize("   ").text()).isEmpty();
    }
}
