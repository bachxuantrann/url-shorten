package com.auradev.url_shortener.service.impl;

import com.auradev.url_shortener.entity.Url;
import com.auradev.url_shortener.enums.UrlStatusEnum;
import com.auradev.url_shortener.exception.AppException;
import com.auradev.url_shortener.exception.ErrorCode;
import com.auradev.url_shortener.repository.UrlRepository;
import com.auradev.url_shortener.utils.UrlCacheService;
import com.auradev.url_shortener.utils.UrlCacheService.CachedUrl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedirectServiceImplTest {

    private static final String CODE = "aB3xK9z";
    private static final String TARGET = "https://example.com/target";
    private static final String HASH = "$2a$12$hash";

    /** Đồng hồ cố định để kiểm soát chính xác thời điểm "bây giờ". */
    private static final LocalDateTime NOW = LocalDateTime.of(2030, 1, 1, 12, 0, 0);
    private static final Clock CLOCK = Clock.fixed(NOW.atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());

    @Mock
    private UrlRepository urlRepository;

    @Mock
    private UrlCacheService urlCacheService;

    @Mock
    private PasswordEncoder passwordEncoder;

    private RedirectServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new RedirectServiceImpl(urlRepository, urlCacheService, CLOCK, passwordEncoder);
    }

    // ===========================
    //  INPUT GUARD
    // ===========================

    @ParameterizedTest
    @ValueSource(strings = {"ab", "api", "API", "actuator", "has.dot", "has space", "swagger-ui"})
    void invalidOrReservedCodesAreNotFoundWithoutTouchingCacheOrDatabase(String code) {
        assertThatThrownBy(() -> service.resolveTarget(code)).satisfies(e -> assertError(e, ErrorCode.URL_NOT_FOUND));
        assertThatThrownBy(() -> service.unlockTarget(code, "pw")).satisfies(e -> assertError(e, ErrorCode.URL_NOT_FOUND));

        verifyNoInteractions(urlRepository, urlCacheService, passwordEncoder);
    }

    // ===========================
    //  CACHE BEHAVIOUR
    // ===========================

    @Test
    void cacheHitNeverQueriesTheDatabase() {
        when(urlCacheService.get(CODE))
                .thenReturn(Optional.of(new CachedUrl(UrlStatusEnum.ACTIVE, null, false, TARGET)));

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
        Url url = url(UrlStatusEnum.ACTIVE, null, null);
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
        when(urlRepository.findByShortCode(CODE)).thenReturn(Optional.of(url(UrlStatusEnum.ACTIVE, null, null)));

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
        when(urlCacheService.get(CODE)).thenReturn(Optional.of(new CachedUrl(status, null, false, TARGET)));

        assertThatThrownBy(() -> service.resolveTarget(CODE)).satisfies(e -> assertError(e, expected));
    }

    @Test
    void activeLinkPastItsExpiryIsGone() {
        when(urlCacheService.get(CODE)).thenReturn(Optional.of(
                new CachedUrl(UrlStatusEnum.ACTIVE, NOW.minusSeconds(1), false, TARGET)));

        assertThatThrownBy(() -> service.resolveTarget(CODE)).satisfies(e -> assertError(e, ErrorCode.URL_GONE));
    }

    @Test
    void activeLinkBeforeItsExpiryRedirects() {
        when(urlCacheService.get(CODE)).thenReturn(Optional.of(
                new CachedUrl(UrlStatusEnum.ACTIVE, NOW.plusHours(1), false, TARGET)));

        assertThat(service.resolveTarget(CODE)).isEqualTo(TARGET);
    }

    // Ca biên: link hết hạn đúng thời điểm user click

    @Test
    void linkExpiringExactlyNowIsAlreadyGone() {
        when(urlCacheService.get(CODE)).thenReturn(Optional.of(
                new CachedUrl(UrlStatusEnum.ACTIVE, NOW, false, TARGET)));

        assertThatThrownBy(() -> service.resolveTarget(CODE)).satisfies(e -> assertError(e, ErrorCode.URL_GONE));
    }

    @Test
    void linkExpiringOneNanosecondFromNowStillRedirects() {
        when(urlCacheService.get(CODE)).thenReturn(Optional.of(
                new CachedUrl(UrlStatusEnum.ACTIVE, NOW.plusNanos(1), false, TARGET)));

        assertThat(service.resolveTarget(CODE)).isEqualTo(TARGET);
    }

    @Test
    void sameCachedLinkFlipsFromRedirectToGoneAsTheClockAdvances() {
        when(urlCacheService.get(CODE)).thenReturn(Optional.of(
                new CachedUrl(UrlStatusEnum.ACTIVE, NOW.plusMinutes(1), false, TARGET)));
        assertThat(service.resolveTarget(CODE)).isEqualTo(TARGET);

        RedirectServiceImpl later = new RedirectServiceImpl(urlRepository, urlCacheService,
                Clock.offset(CLOCK, Duration.ofMinutes(2)), passwordEncoder);

        assertThatThrownBy(() -> later.resolveTarget(CODE)).satisfies(e -> assertError(e, ErrorCode.URL_GONE));
    }

    // ===========================
    //  PASSWORD PROTECTED LINKS: redirect
    // ===========================

    @Test
    void protectedLinkNeverRedirectsAndAsksForThePassword() {
        when(urlCacheService.get(CODE)).thenReturn(Optional.of(
                new CachedUrl(UrlStatusEnum.ACTIVE, null, true, "")));

        assertThatThrownBy(() -> service.resolveTarget(CODE))
                .satisfies(e -> assertError(e, ErrorCode.URL_PASSWORD_REQUIRED));
    }

    @Test
    void protectedLinkLoadedFromTheDatabaseIsCachedWithoutItsTarget() {
        Url url = url(UrlStatusEnum.ACTIVE, null, HASH);
        when(urlCacheService.get(CODE)).thenReturn(Optional.empty());
        when(urlRepository.findByShortCode(CODE)).thenReturn(Optional.of(url));

        assertThatThrownBy(() -> service.resolveTarget(CODE))
                .satisfies(e -> assertError(e, ErrorCode.URL_PASSWORD_REQUIRED));

        verify(urlCacheService).put(CODE, url); // UrlCacheService bỏ URL đích khi link có mật khẩu
    }

    @ParameterizedTest
    @CsvSource({
            "EXPIRED,  URL_GONE",
            "DELETED,  URL_GONE",
            "DISABLED, URL_NOT_FOUND",
            "BLOCKED,  URL_ACCESS_BLOCKED"
    })
    void aProtectedLinkThatIsNotAvailableNeverRevealsThatItHasAPassword(UrlStatusEnum status, ErrorCode expected) {
        when(urlCacheService.get(CODE)).thenReturn(Optional.of(new CachedUrl(status, null, true, "")));

        assertThatThrownBy(() -> service.resolveTarget(CODE)).satisfies(e -> assertError(e, expected));
    }

    @Test
    void anExpiredProtectedLinkIsGoneRatherThanAskingForAPassword() {
        when(urlCacheService.get(CODE)).thenReturn(Optional.of(
                new CachedUrl(UrlStatusEnum.ACTIVE, NOW.minusSeconds(1), true, "")));

        assertThatThrownBy(() -> service.resolveTarget(CODE)).satisfies(e -> assertError(e, ErrorCode.URL_GONE));
    }

    // ===========================
    //  PASSWORD PROTECTED LINKS: unlock
    // ===========================

    @Test
    void unlockReturnsTheTargetWhenThePasswordMatches() {
        when(urlRepository.findByShortCode(CODE)).thenReturn(Optional.of(url(UrlStatusEnum.ACTIVE, null, HASH)));
        when(passwordEncoder.matches("secret", HASH)).thenReturn(true);

        assertThat(service.unlockTarget(CODE, "secret")).isEqualTo(TARGET);
    }

    @Test
    void unlockRejectsAWrongPassword() {
        when(urlRepository.findByShortCode(CODE)).thenReturn(Optional.of(url(UrlStatusEnum.ACTIVE, null, HASH)));
        when(passwordEncoder.matches("wrong", HASH)).thenReturn(false);

        assertThatThrownBy(() -> service.unlockTarget(CODE, "wrong"))
                .satisfies(e -> assertError(e, ErrorCode.URL_PASSWORD_INCORRECT));
    }

    @Test
    void unlockRejectsAMissingPasswordWithoutHashing() {
        when(urlRepository.findByShortCode(CODE)).thenReturn(Optional.of(url(UrlStatusEnum.ACTIVE, null, HASH)));

        assertThatThrownBy(() -> service.unlockTarget(CODE, null))
                .satisfies(e -> assertError(e, ErrorCode.URL_PASSWORD_INCORRECT));

        verifyNoInteractions(passwordEncoder);
    }

    @Test
    void unlockOnALinkWithoutPasswordJustReturnsTheTarget() {
        when(urlRepository.findByShortCode(CODE)).thenReturn(Optional.of(url(UrlStatusEnum.ACTIVE, null, null)));

        assertThat(service.unlockTarget(CODE, "anything")).isEqualTo(TARGET);

        verifyNoInteractions(passwordEncoder);
    }

    @Test
    void unlockOfAnUnknownCodeIsNotFound() {
        when(urlRepository.findByShortCode(CODE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.unlockTarget(CODE, "pw"))
                .satisfies(e -> assertError(e, ErrorCode.URL_NOT_FOUND));
    }

    @ParameterizedTest
    @CsvSource({
            "EXPIRED,  URL_GONE",
            "DELETED,  URL_GONE",
            "DISABLED, URL_NOT_FOUND",
            "BLOCKED,  URL_ACCESS_BLOCKED"
    })
    void unlockAppliesTheStatusRulesBeforeSpendingCpuOnThePassword(UrlStatusEnum status, ErrorCode expected) {
        when(urlRepository.findByShortCode(CODE)).thenReturn(Optional.of(url(status, null, HASH)));

        assertThatThrownBy(() -> service.unlockTarget(CODE, "secret")).satisfies(e -> assertError(e, expected));

        verify(passwordEncoder, never()).matches(anyString(), anyString());
    }

    @Test
    void unlockOfAnExpiredLinkIsGoneEvenWithTheRightPassword() {
        when(urlRepository.findByShortCode(CODE))
                .thenReturn(Optional.of(url(UrlStatusEnum.ACTIVE, NOW.minusSeconds(1), HASH)));

        assertThatThrownBy(() -> service.unlockTarget(CODE, "secret"))
                .satisfies(e -> assertError(e, ErrorCode.URL_GONE));

        verify(passwordEncoder, never()).matches(anyString(), anyString());
    }

    @Test
    void unlockAlwaysReadsTheDatabaseNeverTheCache() {
        when(urlRepository.findByShortCode(CODE)).thenReturn(Optional.of(url(UrlStatusEnum.ACTIVE, null, HASH)));
        when(passwordEncoder.matches("secret", HASH)).thenReturn(true);

        service.unlockTarget(CODE, "secret");

        verifyNoInteractions(urlCacheService);
    }

    // ===========================
    //  HELPERS
    // ===========================

    private Url url(UrlStatusEnum status, LocalDateTime expiresAt, String passwordHash) {
        return Url.builder().shortCode(CODE).originalUrl(TARGET).status(status).expiresAt(expiresAt)
                .passwordHash(passwordHash).build();
    }

    private void assertError(Throwable thrown, ErrorCode expected) {
        assertThat(thrown).isInstanceOf(AppException.class);
        assertThat(((AppException) thrown).getErrorCode()).isEqualTo(expected);
    }
}
