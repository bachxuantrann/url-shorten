package com.auradev.url_shortener.utils;

import com.auradev.url_shortener.config.ShortenerProperties;
import com.auradev.url_shortener.entity.Url;
import com.auradev.url_shortener.enums.UrlStatusEnum;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Redis hỏng không bao giờ được làm hỏng redirect: mọi thao tác cache phải nuốt lỗi.
 */
class UrlCacheServiceTest {

    private RedisTemplate<String, String> redisTemplate;
    private ValueOperations<String, String> valueOps;
    private UrlCacheService cacheService;
    private CircuitBreaker breaker;
    private SimpleMeterRegistry registry;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redisTemplate = mock(RedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        breaker = breaker();
        registry = new SimpleMeterRegistry();
        cacheService = new UrlCacheService(redisTemplate, new ShortenerProperties(), Clock.systemDefaultZone(),
                breaker, new AppMetrics(registry));
    }

    /** Breaker nhỏ để test nhanh: mở sau 4 lần gọi nếu >= 50% lỗi, giữ mở 60 giây. */
    static CircuitBreaker breaker() {
        return CircuitBreaker.of("test", CircuitBreakerConfig.custom()
                .slidingWindowSize(4).minimumNumberOfCalls(4).failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(60)).build());
    }

    private double count(String result) {
        var counter = registry.find("url.cache.requests").tag("result", result).counter();
        return counter == null ? 0 : counter.count();
    }

    // ===========================
    //  CIRCUIT BREAKER + METRIC
    // ===========================

    @Test
    void theBreakerOpensAfterRepeatedFailuresAndRedisIsNoLongerCalled() {
        when(valueOps.get(anyString())).thenThrow(new RedisConnectionFailureException("redis down"));

        for (int i = 0; i < 4; i++) {
            cacheService.get("abc1234");
        }
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        for (int i = 0; i < 10; i++) {
            assertThat(cacheService.get("abc1234")).isEmpty();
        }

        verify(valueOps, org.mockito.Mockito.times(4)).get(anyString()); // 10 lần sau không chạm Redis
        assertThat(count("error")).isEqualTo(4);
        assertThat(count("circuit_open")).isEqualTo(10);
    }

    @Test
    void writesAreSkippedSilentlyWhileTheBreakerIsOpen() {
        breaker.transitionToOpenState();
        Url url = Url.builder().shortCode("abc1234").originalUrl("https://example.com").status(UrlStatusEnum.ACTIVE).build();

        assertThatCode(() -> cacheService.put("abc1234", url)).doesNotThrowAnyException();
        assertThatCode(() -> cacheService.putMissing("abc1234")).doesNotThrowAnyException();

        verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void evictionStillReachesRedisWhileTheBreakerIsOpen() {
        breaker.transitionToOpenState();

        cacheService.evict("abc1234");

        verify(redisTemplate).delete("URL:abc1234");
    }

    @Test
    void cacheResultsAreCountedAsHitMissAndNegativeHit() {
        when(valueOps.get("URL:hit0001")).thenReturn("ACTIVE|-|-|https://example.com");
        when(valueOps.get("URL:neg0001")).thenReturn("MISSING");
        when(valueOps.get("URL:bad0001")).thenReturn("garbage");

        cacheService.get("hit0001");
        cacheService.get("miss001");
        cacheService.get("neg0001");
        cacheService.get("bad0001");

        assertThat(count("hit")).isEqualTo(1);
        assertThat(count("miss")).isEqualTo(1);
        assertThat(count("negative_hit")).isEqualTo(1);
        assertThat(count("corrupt")).isEqualTo(1);
    }

    @Test
    void getReturnsEmptyWhenRedisIsDown() {
        when(valueOps.get(anyString())).thenThrow(new RedisConnectionFailureException("redis down"));

        assertThat(cacheService.get("abc1234")).isEqualTo(Optional.empty());
    }

    @Test
    void writesAndEvictionNeverThrowWhenRedisIsDown() {
        RuntimeException down = new RedisConnectionFailureException("redis down");
        org.mockito.Mockito.doThrow(down).when(valueOps).set(anyString(), anyString(), any(Duration.class));
        when(redisTemplate.delete(anyString())).thenThrow(down);
        Url url = Url.builder().shortCode("abc1234").originalUrl("https://example.com").status(UrlStatusEnum.ACTIVE).build();

        assertThatCode(() -> cacheService.put("abc1234", url)).doesNotThrowAnyException();
        assertThatCode(() -> cacheService.putMissing("abc1234")).doesNotThrowAnyException();
        assertThatCode(() -> cacheService.evict("abc1234")).doesNotThrowAnyException();
        assertThatCode(() -> cacheService.evictAfterCommit("abc1234")).doesNotThrowAnyException();
    }

    @Test
    void evictAllDeletesEveryKeyWithASingleRedisCall() {
        cacheService.evictAll(List.of("aaa1111", "bbb2222", "ccc3333"));

        verify(redisTemplate).delete(List.of("URL:aaa1111", "URL:bbb2222", "URL:ccc3333"));
    }

    @Test
    void evictAllDoesNothingForAnEmptyCollectionAndSurvivesRedisFailure() {
        cacheService.evictAll(List.of());
        cacheService.evictAllAfterCommit(List.of());
        verify(redisTemplate, never()).delete(anyCollection());

        when(redisTemplate.delete(anyCollection())).thenThrow(new RedisConnectionFailureException("redis down"));
        assertThatCode(() -> cacheService.evictAll(List.of("aaa1111"))).doesNotThrowAnyException();
    }

    @Test
    void evictAfterCommitEvictsImmediatelyWhenThereIsNoTransaction() {
        cacheService.evictAfterCommit("abc1234");

        verify(redisTemplate).delete("URL:abc1234");
    }
}
