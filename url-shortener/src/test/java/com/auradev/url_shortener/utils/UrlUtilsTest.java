package com.auradev.url_shortener.utils;

import com.auradev.url_shortener.exception.AppException;
import com.auradev.url_shortener.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UrlUtilsTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "https://example.com",
            "http://example.com/path?query=1#frag",
            "HTTPS://Example.com:8443/a/b",
            "https://sub.example.co.uk/%E2%9C%93",
            "http://192.168.1.10/page"
    })
    void acceptsValidHttpUrls(String url) {
        assertThat(UrlUtils.validate(url)).isEqualTo(url);
    }

    @Test
    void trimsSurroundingWhitespace() {
        assertThat(UrlUtils.validate("  https://example.com/a  ")).isEqualTo("https://example.com/a");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "   ",
            "example.com",
            "ftp://example.com/file",
            "javascript:alert(1)",
            "file:///etc/passwd",
            "data:text/html;base64,AAAA",
            "http://",
            "https:///no-host",
            "http://user:pass@example.com",
            "http://google.com@evil.com",
            "https://exa mple.com"
    })
    void rejectsInvalidUrls(String url) {
        assertThatThrownBy(() -> UrlUtils.validate(url))
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_URL));
    }

    @Test
    void rejectsTooLongUrl() {
        String tooLong = "https://example.com/" + "a".repeat(UrlUtils.MAX_URL_LENGTH);

        assertThatThrownBy(() -> UrlUtils.validate(tooLong)).isInstanceOf(AppException.class);
    }
}
