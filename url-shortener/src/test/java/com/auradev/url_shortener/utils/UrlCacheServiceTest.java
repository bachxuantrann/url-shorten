package com.auradev.url_shortener.utils;

import com.auradev.url_shortener.config.ShortenerProperties;
import com.auradev.url_shortener.entity.Url;
import com.auradev.url_shortener.enums.UrlStatusEnum;
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

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redisTemplate = mock(RedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        cacheService = new UrlCacheService(redisTemplate, new ShortenerProperties(), Clock.systemDefaultZone());
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
