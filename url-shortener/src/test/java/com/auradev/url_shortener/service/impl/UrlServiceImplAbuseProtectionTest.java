package com.auradev.url_shortener.service.impl;

import com.auradev.url_shortener.config.ShortenerProperties;
import com.auradev.url_shortener.dto.request.CreateUrlRequest;
import com.auradev.url_shortener.dto.request.UpdateUrlRequest;
import com.auradev.url_shortener.dto.response.UrlResponse;
import com.auradev.url_shortener.entity.Url;
import com.auradev.url_shortener.enums.UrlStatusEnum;
import com.auradev.url_shortener.exception.AppException;
import com.auradev.url_shortener.exception.ErrorCode;
import com.auradev.url_shortener.mapper.UrlMapper;
import com.auradev.url_shortener.repository.UrlRepository;
import com.auradev.url_shortener.utils.QrCodeGenerator;
import com.auradev.url_shortener.utils.ShortCodeGenerator;
import com.auradev.url_shortener.utils.UrlCacheService;
import com.auradev.url_shortener.utils.UrlTargetPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Hạn mức link mỗi user, link ẩn danh và chính sách URL đích trong {@link UrlServiceImpl}.
 */
@ExtendWith(MockitoExtension.class)
class UrlServiceImplAbuseProtectionTest {

    private static final UUID USER_ID = UUID.randomUUID();

    @Mock
    private UrlRepository urlRepository;
    @Mock
    private UrlMapper urlMapper;
    @Mock
    private ShortCodeGenerator shortCodeGenerator;
    @Mock
    private UrlCacheService urlCacheService;
    @Mock
    private UrlTargetPolicy urlTargetPolicy;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private QrCodeGenerator qrCodeGenerator;

    private ShortenerProperties properties;
    private UrlServiceImpl service;

    @BeforeEach
    void setUp() {
        properties = new ShortenerProperties();
        properties.setMaxLinksPerUser(3);
        service = new UrlServiceImpl(urlRepository, urlMapper, shortCodeGenerator, properties,
                urlCacheService, Clock.systemDefaultZone(), urlTargetPolicy, passwordEncoder,
                new TransactionTemplate(mock(PlatformTransactionManager.class)), qrCodeGenerator);
    }

    // ===========================
    //  QUOTA
    // ===========================

    @Test
    void rejectsCreationWhenTheUserIsAtTheirQuota() {
        when(urlRepository.countByUserIdAndStatusNot(USER_ID, UrlStatusEnum.DELETED)).thenReturn(3L);

        assertThatThrownBy(() -> service.create(USER_ID, request("https://example.com")))
                .isInstanceOfSatisfying(AppException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.URL_QUOTA_EXCEEDED);
                    assertThat(e.getArgs()).containsExactly("3");
                });

        verify(urlRepository, never()).save(any());
        verify(urlRepository, never()).nextShortCodeSequence();
        verifyNoInteractions(urlTargetPolicy);
    }

    @Test
    void allowsCreationBelowTheQuotaAndStoresTheOwner() {
        when(urlRepository.countByUserIdAndStatusNot(USER_ID, UrlStatusEnum.DELETED)).thenReturn(2L);
        stubSuccessfulSave();

        service.create(USER_ID, request("https://example.com/page"));

        Url saved = savedUrl();
        assertThat(saved.getUserId()).isEqualTo(USER_ID);
        assertThat(saved.getOriginalUrl()).isEqualTo("https://example.com/page");
        verify(urlTargetPolicy).check("https://example.com/page");
    }

    @Test
    void deletedLinksDoNotCountTowardsTheQuota() {
        // Truy vấn đếm loại trừ DELETED; test khẳng định đúng tham số được dùng
        when(urlRepository.countByUserIdAndStatusNot(eq(USER_ID), eq(UrlStatusEnum.DELETED))).thenReturn(0L);
        stubSuccessfulSave();

        service.create(USER_ID, request("https://example.com"));

        verify(urlRepository).countByUserIdAndStatusNot(USER_ID, UrlStatusEnum.DELETED);
    }

    @Test
    void aNonPositiveQuotaMeansUnlimitedAndSkipsTheCountQuery() {
        properties.setMaxLinksPerUser(0);
        stubSuccessfulSave();

        service.create(USER_ID, request("https://example.com"));

        verify(urlRepository, never()).countByUserIdAndStatusNot(any(), any());
    }

    // ===========================
    //  ANONYMOUS LINKS
    // ===========================

    @Test
    void anonymousLinksHaveNoOwnerAndAreNotSubjectToTheUserQuota() {
        stubSuccessfulSave();

        service.createAnonymous(request("https://example.com/anon"));

        assertThat(savedUrl().getUserId()).isNull();
        verify(urlRepository, never()).countByUserIdAndStatusNot(any(), any());
        verify(urlTargetPolicy).check("https://example.com/anon");
    }

    // ===========================
    //  TARGET POLICY
    // ===========================

    @Test
    void aRejectedTargetStopsCreationBeforeAnythingIsSaved() {
        doThrow(new AppException(ErrorCode.UNSAFE_URL_TARGET)).when(urlTargetPolicy).check("http://127.0.0.1/");
        when(urlRepository.countByUserIdAndStatusNot(USER_ID, UrlStatusEnum.DELETED)).thenReturn(0L);

        assertThatThrownBy(() -> service.create(USER_ID, request("http://127.0.0.1/")))
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.UNSAFE_URL_TARGET));

        verify(urlRepository, never()).save(any());
        verify(urlRepository, never()).nextShortCodeSequence();
    }

    @Test
    void anonymousCreationAlsoRunsTheTargetPolicy() {
        doThrow(new AppException(ErrorCode.BLOCKED_DOMAIN, "evil.com")).when(urlTargetPolicy).check("https://evil.com/x");

        assertThatThrownBy(() -> service.createAnonymous(request("https://evil.com/x")))
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.BLOCKED_DOMAIN));

        verify(urlRepository, never()).save(any());
    }

    @Test
    void updatingATargetRunsThePolicyAndLeavesTheLinkUntouchedWhenRejected() {
        Url existing = Url.builder().userId(USER_ID).shortCode("aB3xK9z")
                .originalUrl("https://example.com/original").status(UrlStatusEnum.ACTIVE).build();
        when(urlRepository.findByShortCodeAndUserIdAndStatusNot("aB3xK9z", USER_ID, UrlStatusEnum.DELETED))
                .thenReturn(Optional.of(existing));
        doThrow(new AppException(ErrorCode.BLOCKED_DOMAIN, "evil.com")).when(urlTargetPolicy).check("https://evil.com/x");

        UpdateUrlRequest update = new UpdateUrlRequest();
        update.setOriginalUrl("https://evil.com/x");

        assertThatThrownBy(() -> service.update(USER_ID, "aB3xK9z", update))
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.BLOCKED_DOMAIN));

        assertThat(existing.getOriginalUrl()).isEqualTo("https://example.com/original");
        verify(urlRepository, never()).save(any());
        verify(urlCacheService, never()).evictAfterCommit(anyString());
    }

    // ===========================
    //  HELPERS
    // ===========================

    private CreateUrlRequest request(String url) {
        CreateUrlRequest request = new CreateUrlRequest();
        request.setOriginalUrl(url);
        return request;
    }

    private void stubSuccessfulSave() {
        when(urlRepository.nextShortCodeSequence()).thenReturn(1L);
        when(shortCodeGenerator.generate(anyLong())).thenReturn("aB3xK9z");
        when(urlRepository.save(any(Url.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(urlMapper.toUrlResponse(any(Url.class), anyString())).thenReturn(new UrlResponse());
    }

    private Url savedUrl() {
        ArgumentCaptor<Url> captor = ArgumentCaptor.forClass(Url.class);
        verify(urlRepository).save(captor.capture());
        return captor.getValue();
    }
}
