package com.auradev.url_shortener.utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class ShortCodesTest {

    @ParameterizedTest
    @ValueSource(strings = {"api", "API", "Actuator", "swagger-ui", "error", "admin"})
    void reservedWordsAreRecognisedCaseInsensitively(String code) {
        assertThat(ShortCodes.isReserved(code)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"aB3xK9z", "my-github", "x_y-z", "abc"})
    void validFormats(String code) {
        assertThat(ShortCodes.isValidFormat(code)).isTrue();
        assertThat(ShortCodes.isReserved(code)).isFalse();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"ab", "has.dot", "has space", "a/b", "ünï"})
    void invalidFormats(String code) {
        assertThat(ShortCodes.isValidFormat(code)).isFalse();
    }

    @Test
    void lengthLimitsAreEnforced() {
        assertThat(ShortCodes.isValidFormat("a".repeat(ShortCodes.MAX_LENGTH))).isTrue();
        assertThat(ShortCodes.isValidFormat("a".repeat(ShortCodes.MAX_LENGTH + 1))).isFalse();
    }

    @Test
    void redirectPathsAreSingleSegmentNonReserved() {
        assertThat(ShortCodes.isRedirectPath("/aB3xK9z")).isTrue();
        assertThat(ShortCodes.isRedirectPath("/ab")).isTrue(); // độ dài kiểm ở controller → 404
        assertThat(ShortCodes.isRedirectPath("/api")).isFalse();
        assertThat(ShortCodes.isRedirectPath("/actuator")).isFalse();
        assertThat(ShortCodes.isRedirectPath("/api/v1/urls")).isFalse();
        assertThat(ShortCodes.isRedirectPath("/favicon.ico")).isFalse();
        assertThat(ShortCodes.isRedirectPath("/")).isFalse();
        assertThat(ShortCodes.isRedirectPath("")).isFalse();
        assertThat(ShortCodes.isRedirectPath(null)).isFalse();
    }
}
