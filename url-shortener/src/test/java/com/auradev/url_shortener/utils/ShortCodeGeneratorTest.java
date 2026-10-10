package com.auradev.url_shortener.utils;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ShortCodeGeneratorTest {

    private static final String SECRET = "unit-test-secret";

    private final ShortCodeGenerator generator = new ShortCodeGenerator(SECRET);

    @Test
    void generatesFixedLengthBase62Codes() {
        for (long seq : new long[]{0, 1, 2, 12345, 987_654_321L, ShortCodeGenerator.DOMAIN_SIZE - 1}) {
            assertThat(generator.generate(seq))
                    .hasSize(ShortCodeGenerator.CODE_LENGTH)
                    .matches("[0-9a-zA-Z]{7}");
        }
    }

    @Test
    void isDeterministicForSameSecret() {
        assertThat(new ShortCodeGenerator(SECRET).generate(42))
                .isEqualTo(new ShortCodeGenerator(SECRET).generate(42));
    }

    @Test
    void differentSecretsGiveDifferentMappings() {
        ShortCodeGenerator other = new ShortCodeGenerator("another-secret");

        int differing = 0;
        for (long seq = 1; seq <= 100; seq++) {
            if (!generator.generate(seq).equals(other.generate(seq))) {
                differing++;
            }
        }
        assertThat(differing).isGreaterThan(95);
    }

    @Test
    void neverProducesDuplicatesForSequentialInputs() {
        Set<String> codes = new HashSet<>();
        for (long seq = 1; seq <= 200_000; seq++) {
            assertThat(codes.add(generator.generate(seq)))
                    .as("duplicate code for sequence %d", seq)
                    .isTrue();
        }
    }

    @Test
    void consecutiveSequencesDoNotLookSequential() {
        String first  = generator.generate(1);
        String second = generator.generate(2);
        String third  = generator.generate(3);

        // Không chung tiền tố dài, không tăng dần theo thứ tự
        assertThat(first.substring(0, 3)).isNotEqualTo(second.substring(0, 3));
        assertThat(second.substring(0, 3)).isNotEqualTo(third.substring(0, 3));
    }

    @Test
    void rejectsOutOfRangeSequence() {
        assertThatThrownBy(() -> generator.generate(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> generator.generate(ShortCodeGenerator.DOMAIN_SIZE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requiresSecret() {
        assertThatThrownBy(() -> new ShortCodeGenerator(" ")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new ShortCodeGenerator((String) null)).isInstanceOf(IllegalStateException.class);
    }
}
