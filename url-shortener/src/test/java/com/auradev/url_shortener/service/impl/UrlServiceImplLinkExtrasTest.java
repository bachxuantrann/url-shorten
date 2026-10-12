package com.auradev.url_shortener.service.impl;

import com.auradev.url_shortener.config.ShortenerProperties;
import com.auradev.url_shortener.dto.request.CreateUrlRequest;
import com.auradev.url_shortener.dto.response.UrlResponse;
import com.auradev.url_shortener.entity.Url;
import com.auradev.url_shortener.enums.UrlStatusEnum;
import com.auradev.url_shortener.exception.AppException;
import com.auradev.url_shortener.exception.ErrorCode;
import com.auradev.url_shortener.mapper.UrlMapper;
import com.auradev.url_shortener.repository.UrlRepository;
import com.auradev.url_shortener.service.LinkAppealService;
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
import org.springframework.dao.DataIntegrityViolationException;
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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Alias, va chạm với mã sinh tự động (retry), mật khẩu và QR trong {@link UrlServiceImpl}.
 */
@ExtendWith(MockitoExtension.class)
class UrlServiceImplLinkExtrasTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final String COLLISION_MESSAGE =
            "ERROR: duplicate key value violates unique constraint \"uq_urls_short_code\"";

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

    private UrlServiceImpl service;

    @BeforeEach
    void setUp() {
        ShortenerProperties properties = new ShortenerProperties();
        properties.setBaseUrl("https://sho.rt/");
        properties.setMaxLinksPerUser(0); // không liên quan tới các test này
        service = new UrlServiceImpl(urlRepository, urlMapper, shortCodeGenerator, properties, urlCacheService,
                Clock.systemDefaultZone(), urlTargetPolicy, passwordEncoder,
                new TransactionTemplate(mock(PlatformTransactionManager.class)), qrCodeGenerator,
                mock(LinkAppealService.class));

        lenient().when(urlMapper.toUrlResponse(any(Url.class), anyString())).thenReturn(new UrlResponse());
    }

    // ===========================
    //  ALIAS
    // ===========================

    @Test
    void anAliasBecomesTheShortCodeAndNoCodeIsGenerated() {
        when(urlRepository.existsByShortCode("my-link")).thenReturn(false);
        stubSaveReturnsArgument();

        service.create(USER_ID, request("https://example.com", "my-link", null));

        assertThat(savedUrls(1)[0].getShortCode()).isEqualTo("my-link");
        verify(urlRepository, never()).nextShortCodeSequence();
        verifyNoInteractions(shortCodeGenerator);
        verify(urlCacheService).evictAfterCommit("my-link");
    }

    @Test
    void aReservedAliasIsUnavailableWithoutAnyLookupOrWrite() {
        assertThatThrownBy(() -> service.create(USER_ID, request("https://example.com", "admin", null)))
                .satisfies(e -> assertError(e, ErrorCode.URL_ALIAS_UNAVAILABLE));

        verify(urlRepository, never()).existsByShortCode(anyString());
        verify(urlRepository, never()).save(any());
    }

    @Test
    void anAliasAlreadyInUseIsUnavailable() {
        when(urlRepository.existsByShortCode("taken")).thenReturn(true);

        assertThatThrownBy(() -> service.create(USER_ID, request("https://example.com", "taken", null)))
                .satisfies(e -> assertError(e, ErrorCode.URL_ALIAS_UNAVAILABLE));

        verify(urlRepository, never()).save(any());
    }

    @Test
    void aMalformedAliasIsRejectedEvenWhenTheServiceIsCalledDirectly() {
        assertThatThrownBy(() -> service.create(USER_ID, request("https://example.com", "a b", null)))
                .satisfies(e -> assertError(e, ErrorCode.VALIDATION_FAILED));
        assertThatThrownBy(() -> service.create(USER_ID, request("https://example.com", "ab", null)))
                .satisfies(e -> assertError(e, ErrorCode.VALIDATION_FAILED));

        verify(urlRepository, never()).save(any());
    }

    @Test
    void anAliasRaceLostAtInsertTimeIsReportedAsUnavailableWithoutRetrying() {
        when(urlRepository.existsByShortCode("race")).thenReturn(false);
        when(urlRepository.save(any(Url.class)))
                .thenThrow(new DataIntegrityViolationException("insert failed", new RuntimeException(COLLISION_MESSAGE)));

        assertThatThrownBy(() -> service.create(USER_ID, request("https://example.com", "race", null)))
                .satisfies(e -> assertError(e, ErrorCode.URL_ALIAS_UNAVAILABLE));

        verify(urlRepository, times(1)).save(any());
    }

    @Test
    void anonymousLinksIgnoreAnAliasAndPasswordEvenIfTheRequestCarriesThem() {
        when(urlRepository.nextShortCodeSequence()).thenReturn(1L);
        when(shortCodeGenerator.generate(anyLong())).thenReturn("aB3xK9z");
        stubSaveReturnsArgument();

        service.createAnonymous(request("https://example.com", "sneaky-alias", "secret"));

        Url saved = savedUrls(1)[0];
        assertThat(saved.getShortCode()).isEqualTo("aB3xK9z");
        assertThat(saved.getPasswordHash()).isNull();
        assertThat(saved.getUserId()).isNull();
        verifyNoInteractions(passwordEncoder);
    }

    // ===========================
    //  COLLISION WITH A GENERATED CODE
    // ===========================

    @Test
    void aGeneratedCodeThatCollidesIsRetriedWithAFreshCode() {
        when(urlRepository.nextShortCodeSequence()).thenReturn(1L, 2L);
        when(shortCodeGenerator.generate(anyLong())).thenReturn("codeAAA", "codeBBB");
        when(urlRepository.save(any(Url.class)))
                .thenThrow(new DataIntegrityViolationException("insert failed", new RuntimeException(COLLISION_MESSAGE)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.create(USER_ID, request("https://example.com", null, null));

        Url[] attempts = savedUrls(2);
        assertThat(attempts[0].getShortCode()).isEqualTo("codeAAA");
        assertThat(attempts[1].getShortCode()).isEqualTo("codeBBB");
        verify(urlRepository, times(2)).nextShortCodeSequence();
    }

    @Test
    void collisionRetriesAreBounded() {
        when(urlRepository.nextShortCodeSequence()).thenReturn(1L);
        when(shortCodeGenerator.generate(anyLong())).thenReturn("codeAAA");
        when(urlRepository.save(any(Url.class)))
                .thenThrow(new DataIntegrityViolationException("insert failed", new RuntimeException(COLLISION_MESSAGE)));

        assertThatThrownBy(() -> service.create(USER_ID, request("https://example.com", null, null)))
                .isInstanceOf(IllegalStateException.class);

        verify(urlRepository, times(5)).save(any());
    }

    @Test
    void anIntegrityViolationUnrelatedToTheShortCodeIsNotRetried() {
        when(urlRepository.nextShortCodeSequence()).thenReturn(1L);
        when(shortCodeGenerator.generate(anyLong())).thenReturn("codeAAA");
        DataIntegrityViolationException foreignKey = new DataIntegrityViolationException("insert failed",
                new RuntimeException("ERROR: insert violates foreign key constraint \"fk_urls_user\""));
        when(urlRepository.save(any(Url.class))).thenThrow(foreignKey);

        assertThatThrownBy(() -> service.create(USER_ID, request("https://example.com", null, null)))
                .isSameAs(foreignKey);

        verify(urlRepository, times(1)).save(any());
    }

    // ===========================
    //  PASSWORD
    // ===========================

    @Test
    void thePasswordIsHashedAndOnlyTheHashIsStored() {
        when(passwordEncoder.encode("secret")).thenReturn("HASH");
        when(urlRepository.nextShortCodeSequence()).thenReturn(1L);
        when(shortCodeGenerator.generate(anyLong())).thenReturn("aB3xK9z");
        stubSaveReturnsArgument();

        service.create(USER_ID, request("https://example.com", null, "secret"));

        assertThat(savedUrls(1)[0].getPasswordHash()).isEqualTo("HASH");
    }

    @Test
    void noPasswordMeansNothingIsHashed() {
        when(urlRepository.nextShortCodeSequence()).thenReturn(1L);
        when(shortCodeGenerator.generate(anyLong())).thenReturn("aB3xK9z");
        stubSaveReturnsArgument();

        service.create(USER_ID, request("https://example.com", null, null));

        assertThat(savedUrls(1)[0].getPasswordHash()).isNull();
        verifyNoInteractions(passwordEncoder);
    }

    @Test
    void aPasswordOverBcryptsLimitIsRejectedBeforeHashing() {
        String tooLongInBytes = "😀".repeat(19); // 19 ký tự nhưng 76 byte UTF-8

        assertThatThrownBy(() -> service.create(USER_ID, request("https://example.com", null, tooLongInBytes)))
                .satisfies(e -> assertError(e, ErrorCode.INVALID_LINK_PASSWORD));
        assertThatThrownBy(() -> service.setPassword(USER_ID, "aB3xK9z", "a".repeat(73)))
                .satisfies(e -> assertError(e, ErrorCode.INVALID_LINK_PASSWORD));

        verifyNoInteractions(passwordEncoder);
        verify(urlRepository, never()).save(any());
    }

    @Test
    void setPasswordStoresTheHashAndEvictsTheCache() {
        Url url = owned(UrlStatusEnum.ACTIVE);
        when(urlRepository.findByShortCodeAndUserIdAndStatusNot("aB3xK9z", USER_ID, UrlStatusEnum.DELETED))
                .thenReturn(Optional.of(url));
        when(passwordEncoder.encode("new-secret")).thenReturn("NEW-HASH");
        stubSaveReturnsArgument();

        service.setPassword(USER_ID, "aB3xK9z", "new-secret");

        assertThat(url.getPasswordHash()).isEqualTo("NEW-HASH");
        verify(urlCacheService).evictAfterCommit("aB3xK9z");
    }

    @Test
    void removePasswordClearsTheHashAndEvictsTheCache() {
        Url url = owned(UrlStatusEnum.ACTIVE);
        url.setPasswordHash("OLD-HASH");
        when(urlRepository.findByShortCodeAndUserIdAndStatusNot("aB3xK9z", USER_ID, UrlStatusEnum.DELETED))
                .thenReturn(Optional.of(url));
        stubSaveReturnsArgument();

        service.removePassword(USER_ID, "aB3xK9z");

        assertThat(url.getPasswordHash()).isNull();
        verify(urlCacheService).evictAfterCommit("aB3xK9z");
    }

    @Test
    void blockedLinksCannotChangeTheirPassword() {
        Url url = owned(UrlStatusEnum.BLOCKED);
        url.setPasswordHash("OLD-HASH");
        when(urlRepository.findByShortCodeAndUserIdAndStatusNot("aB3xK9z", USER_ID, UrlStatusEnum.DELETED))
                .thenReturn(Optional.of(url));
        when(passwordEncoder.encode(anyString())).thenReturn("HASH");

        assertThatThrownBy(() -> service.setPassword(USER_ID, "aB3xK9z", "secret"))
                .satisfies(e -> assertError(e, ErrorCode.URL_BLOCKED));
        assertThatThrownBy(() -> service.removePassword(USER_ID, "aB3xK9z"))
                .satisfies(e -> assertError(e, ErrorCode.URL_BLOCKED));

        assertThat(url.getPasswordHash()).isEqualTo("OLD-HASH");
        verify(urlRepository, never()).save(any());
    }

    @Test
    void passwordChangesOnSomeoneElsesLinkLookLikeNotFound() {
        when(urlRepository.findByShortCodeAndUserIdAndStatusNot("aB3xK9z", USER_ID, UrlStatusEnum.DELETED))
                .thenReturn(Optional.empty());
        when(passwordEncoder.encode(anyString())).thenReturn("HASH");

        assertThatThrownBy(() -> service.setPassword(USER_ID, "aB3xK9z", "secret"))
                .satisfies(e -> assertError(e, ErrorCode.URL_NOT_FOUND));
        assertThatThrownBy(() -> service.removePassword(USER_ID, "aB3xK9z"))
                .satisfies(e -> assertError(e, ErrorCode.URL_NOT_FOUND));
    }

    // ===========================
    //  QR CODE
    // ===========================

    @Test
    void theQrCodeEncodesTheShortUrlWithoutADoubleSlash() {
        when(urlRepository.findByShortCodeAndUserIdAndStatusNot("aB3xK9z", USER_ID, UrlStatusEnum.DELETED))
                .thenReturn(Optional.of(owned(UrlStatusEnum.ACTIVE)));
        byte[] png = {1, 2, 3};
        when(qrCodeGenerator.png("https://sho.rt/aB3xK9z", 256)).thenReturn(png);

        assertThat(service.qrCode(USER_ID, "aB3xK9z", 256)).isSameAs(png);
    }

    @Test
    void theQrCodeOfSomeoneElsesLinkLooksLikeNotFound() {
        when(urlRepository.findByShortCodeAndUserIdAndStatusNot("aB3xK9z", USER_ID, UrlStatusEnum.DELETED))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.qrCode(USER_ID, "aB3xK9z", 256))
                .satisfies(e -> assertError(e, ErrorCode.URL_NOT_FOUND));

        verifyNoInteractions(qrCodeGenerator);
    }

    // ===========================
    //  HELPERS
    // ===========================

    private CreateUrlRequest request(String url, String alias, String password) {
        CreateUrlRequest request = new CreateUrlRequest();
        request.setOriginalUrl(url);
        request.setAlias(alias);
        request.setPassword(password);
        return request;
    }

    private Url owned(UrlStatusEnum status) {
        return Url.builder().userId(USER_ID).shortCode("aB3xK9z").originalUrl("https://example.com").status(status).build();
    }

    private void stubSaveReturnsArgument() {
        when(urlRepository.save(any(Url.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private Url[] savedUrls(int expectedSaves) {
        ArgumentCaptor<Url> captor = ArgumentCaptor.forClass(Url.class);
        verify(urlRepository, times(expectedSaves)).save(captor.capture());
        return captor.getAllValues().toArray(new Url[0]);
    }

    private void assertError(Throwable thrown, ErrorCode expected) {
        assertThat(thrown).isInstanceOf(AppException.class);
        assertThat(((AppException) thrown).getErrorCode()).isEqualTo(expected);
    }
}
