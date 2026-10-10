package com.auradev.url_shortener;

import com.auradev.url_shortener.entity.Url;
import com.auradev.url_shortener.enums.UrlStatusEnum;
import com.auradev.url_shortener.support.TestcontainersConfiguration;
import com.auradev.url_shortener.utils.UrlCacheService;
import com.auradev.url_shortener.utils.UrlCacheService.CachedUrl;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link UrlCacheService} với Redis thật (Testcontainers).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class UrlCacheServiceIntegrationTest {

    @Autowired
    private UrlCacheService cacheService;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @Test
    void roundTripPreservesStatusExpiryAndUrlsContainingPipes() {
        String code = code();
        LocalDateTime expiry = LocalDateTime.now().plusDays(1).withNano(0);
        cacheService.put(code, url(UrlStatusEnum.DISABLED, expiry, "https://example.com/a?x=1|2|3"));

        CachedUrl cached = cacheService.get(code).orElseThrow();

        assertThat(cached.status()).isEqualTo(UrlStatusEnum.DISABLED);
        assertThat(cached.expiresAt()).isEqualTo(expiry);
        assertThat(cached.originalUrl()).isEqualTo("https://example.com/a?x=1|2|3");
        assertThat(cached.isMissing()).isFalse();
    }

    @Test
    void linkWithoutExpiryUsesTheDefaultTtl() {
        String code = code();

        cacheService.put(code, url(UrlStatusEnum.ACTIVE, null, "https://example.com"));

        assertThat(cacheService.get(code).orElseThrow().expiresAt()).isNull();
        assertThat(redisTemplate.getExpire("URL:" + code)).isBetween(590L, 600L);
    }

    @Test
    void ttlIsCappedAtTheRemainingTimeUntilExpiry() {
        String code = code();

        cacheService.put(code, url(UrlStatusEnum.ACTIVE, LocalDateTime.now().plusSeconds(30), "https://example.com"));

        assertThat(redisTemplate.getExpire("URL:" + code)).isBetween(1L, 30L);
    }

    @Test
    void alreadyExpiredLinkIsCachedOnlyBriefly() {
        String code = code();

        cacheService.put(code, url(UrlStatusEnum.ACTIVE, LocalDateTime.now().minusHours(1), "https://example.com"));

        assertThat(redisTemplate.getExpire("URL:" + code)).isBetween(1L, 60L);
    }

    @Test
    void missingMarkerEvictAndCorruptedEntries() {
        String code = code();

        cacheService.putMissing(code);
        assertThat(cacheService.get(code).orElseThrow().isMissing()).isTrue();
        assertThat(redisTemplate.getExpire("URL:" + code)).isBetween(1L, 60L);

        cacheService.evict(code);
        assertThat(cacheService.get(code)).isEmpty();

        redisTemplate.opsForValue().set("URL:" + code, "NOT_A_STATUS|-|https://example.com");
        assertThat(cacheService.get(code)).isEmpty();
        redisTemplate.opsForValue().set("URL:" + code, "ACTIVE|not-a-date|https://example.com");
        assertThat(cacheService.get(code)).isEmpty();
        redisTemplate.delete("URL:" + code);
    }

    private Url url(UrlStatusEnum status, LocalDateTime expiresAt, String originalUrl) {
        return Url.builder().shortCode("x").originalUrl(originalUrl).status(status).expiresAt(expiresAt).build();
    }

    private String code() {
        return "cache" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
}
