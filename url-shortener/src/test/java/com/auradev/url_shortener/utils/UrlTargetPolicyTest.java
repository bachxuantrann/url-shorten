package com.auradev.url_shortener.utils;

import com.auradev.url_shortener.config.ShortenerProperties;
import com.auradev.url_shortener.exception.AppException;
import com.auradev.url_shortener.exception.ErrorCode;
import com.auradev.url_shortener.repository.BlockedDomainRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UrlTargetPolicyTest {

    @Mock
    private BlockedDomainRepository blockedDomainRepository;

    private ShortenerProperties properties;
    private UrlTargetPolicy policy;

    @BeforeEach
    void setUp() {
        properties = new ShortenerProperties();
        properties.setBaseUrl("https://sho.rt");
        properties.setOwnHosts(List.of("www.sho.rt"));
        policy = new UrlTargetPolicy(properties, blockedDomainRepository);
    }

    // ===========================
    //  SELF REDIRECT LOOPS
    // ===========================

    @ParameterizedTest
    @ValueSource(strings = {
            "https://sho.rt/abc", "https://SHO.RT:8443/x", "https://sho.rt./x", "http://www.sho.rt/", "https://sho.rt"
    })
    void rejectsLinksToTheServiceItself(String url) {
        assertThatThrownBy(() -> policy.check(url)).satisfies(e -> assertError(e, ErrorCode.UNSAFE_URL_TARGET));
        verifyNoInteractions(blockedDomainRepository);
    }

    @Test
    void ownHostCheckAppliesEvenWhenPrivateTargetBlockingIsOff() {
        properties.setBlockPrivateTargets(false);

        assertThatThrownBy(() -> policy.check("https://sho.rt/abc"))
                .satisfies(e -> assertError(e, ErrorCode.UNSAFE_URL_TARGET));
    }

    @Test
    void unrelatedSubdomainOfTheOwnDomainIsNotTreatedAsOwnHost() {
        assertThatCode(() -> policy.check("https://blog.sho.rt/post")).doesNotThrowAnyException();
    }

    // ===========================
    //  PRIVATE TARGETS
    // ===========================

    @ParameterizedTest
    @ValueSource(strings = {
            "http://localhost:3000/x", "http://127.0.0.1/", "http://10.0.0.5/admin",
            "http://169.254.169.254/latest/meta-data/", "http://[::1]:8080/", "http://2130706433/",
            "http://intranet/", "https://printer.local/", "http://192.168.1.1/router"
    })
    void rejectsPrivateAndInternalTargetsWithoutHittingTheDatabase(String url) {
        assertThatThrownBy(() -> policy.check(url)).satisfies(e -> assertError(e, ErrorCode.UNSAFE_URL_TARGET));
        verifyNoInteractions(blockedDomainRepository);
    }

    /**
     * Các cách viết khác của 127.0.0.1 không bao giờ được lọt qua chuỗi kiểm tra: tuỳ dạng mà
     * {@code UrlUtils.validate} từ chối (host không hợp lệ theo {@code java.net.URI}) hoặc chính sách
     * chặn như địa chỉ nội bộ. Test khẳng định kết quả chung, không phụ thuộc lớp nào bắt.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "http://0x7f.0.0.1/", "http://0177.0.0.1/", "http://127.1/", "http://017700000001/",
            "http://0x7f000001/", "http://2130706433/", "http://0.0.0.0/", "http://[::ffff:7f00:1]/"
    })
    void obfuscatedLoopbackNeverGetsThroughValidationAndPolicy(String url) {
        assertThatThrownBy(() -> policy.check(UrlUtils.validate(url)))
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getErrorCode()).isIn(ErrorCode.INVALID_URL, ErrorCode.UNSAFE_URL_TARGET));
    }

    @Test
    void privateTargetsAreAllowedWhenBlockingIsSwitchedOff() {
        properties.setBlockPrivateTargets(false);

        assertThatCode(() -> policy.check("http://localhost:3000/dev")).doesNotThrowAnyException();
        assertThatCode(() -> policy.check("http://10.0.0.5/")).doesNotThrowAnyException();
    }

    // ===========================
    //  BLOCKLIST
    // ===========================

    @Test
    void looksUpTheHostAndAllParentDomainsInOneQuery() {
        policy.check("https://a.b.evil.com/x");

        ArgumentCaptor<Collection<String>> captor = ArgumentCaptor.captor();
        verify(blockedDomainRepository, times(1)).findBlockedDomainsIn(captor.capture());
        assertThat(captor.getValue()).containsExactly("a.b.evil.com", "b.evil.com", "evil.com");
    }

    @Test
    void rejectsHostsInTheBlocklistAndReportsTheMatchedDomain() {
        when(blockedDomainRepository.findBlockedDomainsIn(anyCollection())).thenReturn(List.of("evil.com"));

        assertThatThrownBy(() -> policy.check("https://phish.evil.com/login"))
                .isInstanceOfSatisfying(AppException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.BLOCKED_DOMAIN);
                    assertThat(e.getArgs()).containsExactly("evil.com");
                });
    }

    @Test
    void allowsHostsThatAreNotInTheBlocklist() {
        when(blockedDomainRepository.findBlockedDomainsIn(anyCollection())).thenReturn(List.of());

        assertThatCode(() -> policy.check("https://example.com/page")).doesNotThrowAnyException();
    }

    @Test
    void skipsTheQueryWhenThereAreNoCandidateDomains() {
        properties.setBlockPrivateTargets(false);

        policy.check("http://localhost:3000/dev"); // một nhãn → không có domain ứng viên

        verifyNoInteractions(blockedDomainRepository);
    }

    private void assertError(Throwable thrown, ErrorCode expected) {
        assertThat(thrown).isInstanceOf(AppException.class);
        assertThat(((AppException) thrown).getErrorCode()).isEqualTo(expected);
    }
}
