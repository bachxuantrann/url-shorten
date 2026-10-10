package com.auradev.url_shortener.service.impl;

import com.auradev.url_shortener.entity.Url;
import com.auradev.url_shortener.enums.UrlStatusEnum;
import com.auradev.url_shortener.exception.AppException;
import com.auradev.url_shortener.exception.ErrorCode;
import com.auradev.url_shortener.repository.UrlRepository;
import com.auradev.url_shortener.utils.UrlCacheService;
import com.auradev.url_shortener.utils.UrlCacheService.CachedUrl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedirectServiceImplTest {

    private static final String CODE = "aB3xK9z";
    private static final String TARGET = "https://example.com/target";

    @Mock
    private UrlRepository urlRepository;

    @Mock
    private UrlCacheService urlCacheService;

    @InjectMocks
    private RedirectServiceImpl service;

    // ===========================
    //  INPUT GUARD
    // ===========================

    @ParameterizedTest
    @ValueSource(strings = {"ab", "api", "API", "actuator", "has.dot", "has space", "swagger-ui"})
    void invalidOrReservedCodesAreNotFoundWithoutTouchingCacheOrDatabase(String code) {
        assertThatThrownBy(() -> service.resolveTarget(code)).satisfies(e -> assertError(e, ErrorCode.URL_NOT_FOUND));

        verifyNoInteractions(urlRepository, urlCacheService);
    }

    // ===========================
    //  CACHE BEHAVIOUR
    // ===========================

    @Test
    void cacheHitNeverQueriesTheDatabase() {
        when(urlCacheService.get(CODE))
                .thenReturn(Optional.of(new CachedUrl(UrlStatusEnum.ACTIVE, null, TARGET)));

        assertThat(service.resolveTarget(CODE)).isEqualTo(TARGET);

        verifyNoInteractions(urlRepository);
    }

    @Test
    void cachedMissingIsNotFoundWithoutQueryingTheDatabase() {
        when(urlCacheService.get(CODE)).thenReturn(Optional.of(UrlCacheService.MISSING));

        assertThatThrownBy(() -> service.resolveTarget(CODE)).satisfies(e -> assertError(e, ErrorCode.URL_NOT_FOUND));

        verifyNoInteractions(urlRepository);
    }

    @Test
    void cacheMissLoadsFromDatabaseAndPopulatesCache() {
        Url url = url(UrlStatusEnum.ACTIVE, null);
        when(urlCacheService.get(CODE)).thenReturn(Optional.empty());
        when(urlRepository.findByShortCode(CODE)).thenReturn(Optional.of(url));

        assertThat(service.resolveTarget(CODE)).isEqualTo(TARGET);

        verify(urlCacheService).put(CODE, url);
    }

    @Test
    void unknownCodeIsNegativeCached() {
        when(urlCacheService.get(CODE)).thenReturn(Optional.empty());
        when(urlRepository.findByShortCode(CODE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolveTarget(CODE)).satisfies(e -> assertError(e, ErrorCode.URL_NOT_FOUND));

        verify(urlCacheService).putMissing(CODE);
        verify(urlCacheService, never()).put(any(), any());
    }

    @Test
    void databaseIsUsedWhenRedisIsUnavailable() {
        // UrlCacheService nuốt lỗi Redis và trả Optional.empty() (không ném exception)
        when(urlCacheService.get(CODE)).thenReturn(Optional.empty());
        when(urlRepository.findByShortCode(CODE)).thenReturn(Optional.of(url(UrlStatusEnum.ACTIVE, null)));

        assertThat(service.resolveTarget(CODE)).isEqualTo(TARGET);
    }

    // ===========================
    //  STATUS DECISION TABLE
    // ===========================

    @ParameterizedTest
    @CsvSource({
            "EXPIRED,  URL_GONE",
            "DELETED,  URL_GONE",
            "DISABLED, URL_NOT_FOUND",
            "BLOCKED,  URL_ACCESS_BLOCKED"
    })
    void nonActiveStatusesMapToTheExpectedError(UrlStatusEnum status, ErrorCode expected) {
        when(urlCacheService.get(CODE)).thenReturn(Optional.of(new CachedUrl(status, null, TARGET)));

        assertThatThrownBy(() -> service.resolveTarget(CODE)).satisfies(e -> assertError(e, expected));
    }

    @Test
    void activeLinkPastItsExpiryIsGone() {
        when(urlCacheService.get(CODE)).thenReturn(Optional.of(
                new CachedUrl(UrlStatusEnum.ACTIVE, LocalDateTime.now().minusSeconds(1), TARGET)));

        assertThatThrownBy(() -> service.resolveTarget(CODE)).satisfies(e -> assertError(e, ErrorCode.URL_GONE));
    }

    @Test
    void activeLinkBeforeItsExpiryRedirects() {
        when(urlCacheService.get(CODE)).thenReturn(Optional.of(
                new CachedUrl(UrlStatusEnum.ACTIVE, LocalDateTime.now().plusHours(1), TARGET)));

        assertThat(service.resolveTarget(CODE)).isEqualTo(TARGET);
    }

    // ===========================
    //  HELPERS
    // ===========================

    private Url url(UrlStatusEnum status, LocalDateTime expiresAt) {
        return Url.builder().shortCode(CODE).originalUrl(TARGET).status(status).expiresAt(expiresAt).build();
    }

    private void assertError(Throwable thrown, ErrorCode expected) {
        assertThat(thrown).isInstanceOf(AppException.class);
        assertThat(((AppException) thrown).getErrorCode()).isEqualTo(expected);
    }
}
