package com.auradev.url_shortener.utils;

import com.auradev.url_shortener.exception.AppException;
import com.auradev.url_shortener.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HostUtilsTest {

    // ===========================
    //  PRIVATE / INTERNAL HOSTS
    // ===========================

    @ParameterizedTest
    @ValueSource(strings = {
            // tên miền cục bộ / nội bộ
            "localhost", "foo.localhost", "printer.local", "db.internal", "x.localdomain",
            "router.lan", "nas.home.arpa", "intranet",
            // IPv4 riêng, loopback, link-local (cloud metadata), reserved
            "127.0.0.1", "127.1", "10.0.0.1", "172.16.0.1", "172.31.255.255", "192.168.1.1",
            "169.254.169.254", "0.0.0.0", "0.1.2.3", "100.64.0.1", "198.18.0.1", "224.0.0.1",
            "255.255.255.255",
            // các cách viết khác của 127.0.0.1
            "2130706433", "0x7f000001", "0x7f.0.0.1", "0177.0.0.1", "017700000001",
            // số không hợp lệ/mơ hồ → chặn cho chắc
            "0999.1", "999999999999",
            // IPv6
            "[::1]", "[::]", "[fc00::1]", "[fd12:3456::1]", "[fe80::1]", "[fec0::1]", "[ff02::1]",
            "[::ffff:127.0.0.1]", "[::ffff:10.0.0.1]", "[::127.0.0.1]", "[64:ff9b::7f00:1]",
            "[2002:7f00:1::1]", "[fe80::1%eth0]",
            // dấu chấm cuối và chữ hoa vẫn bị chặn
            "LOCALHOST", "localhost."
    })
    void blocksPrivateAndInternalHosts(String host) {
        assertThat(HostUtils.isPrivateOrInternalHost(HostUtils.normalizeHost(host)))
                .as(host)
                .isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "example.com", "sub.example.co.uk", "8.8.8.8", "1.1.1.1", "172.32.0.1", "172.15.255.255",
            "192.169.0.1", "100.63.255.255", "100.128.0.1", "11.0.0.1", "223.255.255.255",
            "[2001:4860:4860::8888]", "localhost.example.com", "notlocalhost.com", "my.local.example.com",
            "localnet.com", "xn--bcher-kva.example"
    })
    void allowsPublicHosts(String host) {
        assertThat(HostUtils.isPrivateOrInternalHost(HostUtils.normalizeHost(host)))
                .as(host)
                .isFalse();
    }

    @Test
    void parsesIpv4InEveryNotation() {
        assertThat(HostUtils.parseIpv4("127.0.0.1")).isEqualTo(2130706433L);
        assertThat(HostUtils.parseIpv4("127.1")).isEqualTo(2130706433L);
        assertThat(HostUtils.parseIpv4("2130706433")).isEqualTo(2130706433L);
        assertThat(HostUtils.parseIpv4("0x7f.0.0.1")).isEqualTo(2130706433L);
        assertThat(HostUtils.parseIpv4("0177.0.0.1")).isEqualTo(2130706433L);
        assertThat(HostUtils.parseIpv4("256.0.0.1")).isNull();
        assertThat(HostUtils.parseIpv4("1.2.3.256")).isNull();
        assertThat(HostUtils.parseIpv4("4294967296")).isNull();
        assertThat(HostUtils.parseIpv4("0x")).isNull();
    }

    // ===========================
    //  NORMALISATION
    // ===========================

    @Test
    void normalizeHostLowercasesAndStripsTrailingDotAndBrackets() {
        assertThat(HostUtils.normalizeHost("EXAMPLE.com.")).isEqualTo("example.com");
        assertThat(HostUtils.normalizeHost("[::1]")).isEqualTo("::1");
        assertThat(HostUtils.normalizeHost(null)).isNull();
    }

    @Test
    void normalizeDomainCleansUpAdminInput() {
        assertThat(HostUtils.normalizeDomain("  *.Evil.COM. ")).isEqualTo("evil.com");
        assertThat(HostUtils.normalizeDomain(".evil.com")).isEqualTo("evil.com");
        assertThat(HostUtils.normalizeDomain("a-b.example.co.uk")).isEqualTo("a-b.example.co.uk");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "localhost", "a_b.com", "evil..com", "-bad.com", "bad-.com",
            "http://evil.com", "evil.com/path", "evil com", "evil.com:8080"})
    void normalizeDomainRejectsInvalidInput(String raw) {
        assertThatThrownBy(() -> HostUtils.normalizeDomain(raw))
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_DOMAIN));
    }

    @Test
    void normalizeDomainRejectsNullAndOverlongDomains() {
        assertThatThrownBy(() -> HostUtils.normalizeDomain(null)).isInstanceOf(AppException.class);
        assertThatThrownBy(() -> HostUtils.normalizeDomain("a".repeat(250) + ".com")).isInstanceOf(AppException.class);
    }

    // ===========================
    //  BLOCKLIST MATCHING
    // ===========================

    @Test
    void candidatesAreTheHostAndEveryParentDomainWithAtLeastTwoLabels() {
        assertThat(HostUtils.blocklistCandidates("a.b.example.com"))
                .containsExactly("a.b.example.com", "b.example.com", "example.com");
        assertThat(HostUtils.blocklistCandidates("example.com")).containsExactly("example.com");
        assertThat(HostUtils.blocklistCandidates("localhost")).isEmpty();
    }

    @Test
    void ipLiteralsOnlyMatchExactly() {
        assertThat(HostUtils.blocklistCandidates("1.2.3.4")).containsExactly("1.2.3.4");
        assertThat(HostUtils.blocklistCandidates("2001:db8::1")).containsExactly("2001:db8::1");
    }

    @Test
    void urlHostRegexMatchesTheDomainAndItsSubdomainsOnly() {
        Pattern regex = Pattern.compile(HostUtils.urlHostRegex("evil.com"), Pattern.CASE_INSENSITIVE);

        for (String url : List.of("https://evil.com", "https://evil.com/x", "http://sub.evil.com:8080/",
                "https://a.b.evil.com/?q=1", "https://EVIL.com", "https://evil.com.", "https://evil.com?x=1",
                "https://evil.com#frag")) {
            assertThat(regex.matcher(url).find()).as(url).isTrue();
        }
        for (String url : List.of("https://notevil.com", "https://evil.com.au", "https://evil.community",
                "https://a.com/?u=https://evil.com", "https://xevil.com/evil.com", "ftp://evil.com")) {
            assertThat(regex.matcher(url).find()).as(url).isFalse();
        }
    }
}
