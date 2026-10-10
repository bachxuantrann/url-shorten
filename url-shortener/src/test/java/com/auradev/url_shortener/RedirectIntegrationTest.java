package com.auradev.url_shortener;

import com.auradev.url_shortener.entity.Url;
import com.auradev.url_shortener.enums.UrlStatusEnum;
import com.auradev.url_shortener.repository.UrlRepository;
import com.auradev.url_shortener.support.ApiClient;
import com.auradev.url_shortener.support.ApiClient.Resp;
import com.auradev.url_shortener.support.TestcontainersConfiguration;
import com.auradev.url_shortener.utils.UrlCacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test end-to-end đường redirect công khai {@code GET /{code}} (security, Redis cache, DB).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RedirectIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private UrlRepository urlRepository;

    @Autowired
    private UrlCacheService urlCacheService;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    private ApiClient api;
    private String owner;

    @BeforeEach
    void setUp() throws Exception {
        api = new ApiClient(port);
        owner = api.registerAndLogin();
    }

    // ===========================
    //  HAPPY PATH
    // ===========================

    @Test
    void activeLinkRedirectsWith302WithoutAuthentication() throws Exception {
        String code = api.createUrl(owner, "https://example.com/landing?utm=1");

        Resp resp = api.call("GET", "/" + code, null, null);

        assertThat(resp.status()).isEqualTo(302);
        assertThat(resp.header("Location")).isEqualTo("https://example.com/landing?utm=1");
        assertThat(resp.header("Cache-Control")).contains("no-store");
    }

    @Test
    void headRequestAlsoRedirects() throws Exception {
        String code = api.createUrl(owner, "https://example.com/head");

        Resp resp = api.call("HEAD", "/" + code, null, null);

        assertThat(resp.status()).isEqualTo(302);
        assertThat(resp.header("Location")).isEqualTo("https://example.com/head");
    }

    @Test
    void invalidBearerTokenDoesNotBreakPublicRedirect() throws Exception {
        String code = api.createUrl(owner, "https://example.com/garbage-token");

        Resp resp = api.call("GET", "/" + code, "this.is.not-a-jwt", null);

        assertThat(resp.status()).isEqualTo(302);
    }

    // ===========================
    //  NOT FOUND / RESERVED
    // ===========================

    @Test
    void unknownOrMalformedCodesReturn404Json() throws Exception {
        for (String code : new String[]{"missing" + shortId(), "ab", "x".repeat(33), "bad-code_ok1"}) {
            Resp resp = api.call("GET", "/" + code, null, null);
            assertThat(resp.status()).as(code).isEqualTo(404);
            assertThat(resp.code()).as(code).isEqualTo("ERR_400");
        }
    }

    @Test
    void reservedPathsAreNotPublicRedirects() throws Exception {
        assertThat(api.call("GET", "/api", null, null).status()).isEqualTo(401);
        assertThat(api.call("GET", "/actuator", null, null).status()).isEqualTo(401);
        assertThat(api.call("GET", "/admin", null, null).status()).isEqualTo(401);
    }

    @Test
    void onlyGetAndHeadAreAllowedWithoutAuthentication() throws Exception {
        String code = api.createUrl(owner, "https://example.com/post");

        assertThat(api.call("POST", "/" + code, null, null).status()).isEqualTo(401);
        assertThat(api.call("DELETE", "/" + code, null, null).status()).isEqualTo(401);
    }

    // ===========================
    //  STATUS RULES
    // ===========================

    @Test
    void disabledLinkReturns404AndEnablingRestoresRedirectImmediately() throws Exception {
        String code = api.createUrl(owner, "https://example.com/toggle");
        assertThat(api.call("GET", "/" + code, null, null).status()).isEqualTo(302); // nạp cache

        api.call("PUT", "/api/v1/urls/" + code + "/disable", owner, null);
        Resp disabled = api.call("GET", "/" + code, null, null);
        assertThat(disabled.status()).isEqualTo(404);
        assertThat(disabled.code()).isEqualTo("ERR_400");

        api.call("PUT", "/api/v1/urls/" + code + "/enable", owner, null);
        assertThat(api.call("GET", "/" + code, null, null).status()).isEqualTo(302);
    }

    @Test
    void updatingTargetTakesEffectImmediately() throws Exception {
        String code = api.createUrl(owner, "https://example.com/old");
        assertThat(api.call("GET", "/" + code, null, null).header("Location")).isEqualTo("https://example.com/old");

        api.call("PUT", "/api/v1/urls/" + code, owner, "{\"originalUrl\":\"https://example.com/new\"}");

        assertThat(api.call("GET", "/" + code, null, null).header("Location")).isEqualTo("https://example.com/new");
    }

    @Test
    void deletedLinkReturns410() throws Exception {
        String code = api.createUrl(owner, "https://example.com/bye");
        assertThat(api.call("GET", "/" + code, null, null).status()).isEqualTo(302);

        api.call("DELETE", "/api/v1/urls/" + code, owner, null);

        Resp resp = api.call("GET", "/" + code, null, null);
        assertThat(resp.status()).isEqualTo(410);
        assertThat(resp.code()).isEqualTo("ERR_405");
    }

    @Test
    void linkPastItsExpiryReturns410EvenWhileStatusIsStillActive() throws Exception {
        String code = api.createUrl(owner, "https://example.com/late");
        Url url = urlRepository.findByShortCode(code).orElseThrow();
        url.setExpiresAt(LocalDateTime.now().minusMinutes(5));
        urlRepository.save(url);
        urlCacheService.evict(code);

        Resp resp = api.call("GET", "/" + code, null, null);

        assertThat(resp.status()).isEqualTo(410);
        assertThat(resp.code()).isEqualTo("ERR_405");
    }

    @Test
    void expiredStatusReturns410AndBlockedReturns403() throws Exception {
        String expired = api.createUrl(owner, "https://example.com/expired");
        String blocked = api.createUrl(owner, "https://example.com/blocked");
        setStatus(expired, UrlStatusEnum.EXPIRED);
        setStatus(blocked, UrlStatusEnum.BLOCKED);

        Resp expiredResp = api.call("GET", "/" + expired, null, null);
        assertThat(expiredResp.status()).isEqualTo(410);

        Resp blockedResp = api.call("GET", "/" + blocked, null, null);
        assertThat(blockedResp.status()).isEqualTo(403);
        assertThat(blockedResp.code()).isEqualTo("ERR_406");
    }

    // ===========================
    //  CACHE
    // ===========================

    @Test
    void firstRedirectPopulatesCacheWithTtl() throws Exception {
        String code = api.createUrl(owner, "https://example.com/cache?a=1%7C2");
        String key = "URL:" + code;
        assertThat(redisTemplate.hasKey(key)).isFalse();

        api.call("GET", "/" + code, null, null);

        assertThat(redisTemplate.opsForValue().get(key)).isEqualTo("ACTIVE|-|https://example.com/cache?a=1%7C2");
        assertThat(redisTemplate.getExpire(key)).isBetween(1L, 600L);
    }

    @Test
    void unknownCodeIsNegativeCachedBriefly() throws Exception {
        String code = "missing" + shortId();

        assertThat(api.call("GET", "/" + code, null, null).status()).isEqualTo(404);

        String key = "URL:" + code;
        assertThat(redisTemplate.opsForValue().get(key)).isEqualTo("MISSING");
        assertThat(redisTemplate.getExpire(key)).isBetween(1L, 60L);
    }

    @Test
    void redirectStillWorksWhenCacheEntryIsCorrupted() throws Exception {
        String code = api.createUrl(owner, "https://example.com/corrupt");
        redisTemplate.opsForValue().set("URL:" + code, "garbage-without-separators");

        Resp resp = api.call("GET", "/" + code, null, null);

        assertThat(resp.status()).isEqualTo(302);
        assertThat(resp.header("Location")).isEqualTo("https://example.com/corrupt");
    }

    // ===========================
    //  HELPERS
    // ===========================

    private void setStatus(String code, UrlStatusEnum status) {
        Url url = urlRepository.findByShortCode(code).orElseThrow();
        url.setStatus(status);
        urlRepository.save(url);
        urlCacheService.evict(code);
    }

    private String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
}
