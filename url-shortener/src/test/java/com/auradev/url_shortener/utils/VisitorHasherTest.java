package com.auradev.url_shortener.utils;

import com.auradev.url_shortener.config.ClickProperties;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VisitorHasherTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 11);

    private VisitorHasher hasher(String secret) {
        ClickProperties properties = new ClickProperties();
        properties.setVisitorSecret(secret);
        return new VisitorHasher(properties);
    }

    @Test
    void sameVisitorSameDayGivesTheSameHash() {
        VisitorHasher hasher = hasher("secret-1");

        assertThat(hasher.hash("203.0.113.7", "UA", DAY)).isEqualTo(hasher.hash("203.0.113.7", "UA", DAY));
    }

    @Test
    void hashIs32HexCharactersAndDoesNotContainTheIp() {
        String hash = hasher("secret-1").hash("203.0.113.7", "UA", DAY);

        assertThat(hash).matches("[0-9a-f]{32}");
        assertThat(hash).doesNotContain("203").doesNotContain("113");
    }

    @Test
    void differentIpUserAgentOrDayGiveDifferentHashes() {
        VisitorHasher hasher = hasher("secret-1");
        String base = hasher.hash("203.0.113.7", "UA", DAY);

        assertThat(hasher.hash("203.0.113.8", "UA", DAY)).isNotEqualTo(base);
        assertThat(hasher.hash("203.0.113.7", "UA2", DAY)).isNotEqualTo(base);
        assertThat(hasher.hash("203.0.113.7", "UA", DAY.plusDays(1))).isNotEqualTo(base);
    }

    @Test
    void differentSecretsGiveDifferentHashes() {
        assertThat(hasher("secret-1").hash("203.0.113.7", "UA", DAY))
                .isNotEqualTo(hasher("secret-2").hash("203.0.113.7", "UA", DAY));
    }

    @Test
    void toleratesMissingIpAndUserAgent() {
        assertThat(hasher("secret-1").hash(null, null, DAY)).matches("[0-9a-f]{32}");
    }

    @Test
    void failsFastWithoutASecret() {
        assertThatThrownBy(() -> hasher(null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> hasher("  ")).isInstanceOf(IllegalStateException.class);
    }
}
