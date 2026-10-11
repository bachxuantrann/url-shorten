package com.auradev.url_shortener.utils;

import com.auradev.url_shortener.config.RateLimitProperties.Rule;
import com.auradev.url_shortener.exception.RateLimitExceededException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Logic quyết định của {@link RateLimitService} với Redis giả lập (hành vi với Redis thật nằm ở
 * {@code RateLimitServiceIntegrationTest}).
 */
class RateLimitServiceTest {

    private static final Rule RULE = new Rule(10, Duration.ofMinutes(2));

    private RedisTemplate<String, String> redisTemplate;
    private RateLimitService service;
    private CircuitBreaker breaker;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redisTemplate = mock(RedisTemplate.class);
        breaker = CircuitBreaker.of("test", CircuitBreakerConfig.custom()
                .slidingWindowSize(4).minimumNumberOfCalls(4).failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(60)).build());
        service = new RateLimitService(redisTemplate, breaker);
    }

    @Test
    void theBreakerOpensAfterRepeatedRedisFailuresAndLimitsAreSkippedWithoutCallingRedis() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new RedisConnectionFailureException("redis down"));

        for (int i = 0; i < 4; i++) {
            service.check("bucket", "subject", RULE);
        }
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        for (int i = 0; i < 10; i++) {
            assertThatCode(() -> service.check("bucket", "subject", RULE)).doesNotThrowAnyException();
        }

        org.mockito.Mockito.verify(redisTemplate, org.mockito.Mockito.times(4))
                .execute(any(RedisScript.class), anyList(), any(Object[].class));
    }

    @Test
    void allowsRequestsUpToAndIncludingTheLimit() {
        stubScript(List.of(10L, 100L));

        assertThatCode(() -> service.check("bucket", "subject", RULE)).doesNotThrowAnyException();
    }

    @Test
    void rejectsTheRequestAfterTheLimitAndReportsHowLongToWait() {
        stubScript(List.of(11L, 87L));

        assertThatThrownBy(() -> service.check("bucket", "subject", RULE))
                .isInstanceOfSatisfying(RateLimitExceededException.class,
                        e -> assertThat(e.getRetryAfterSeconds()).isEqualTo(87));
    }

    @Test
    void fallsBackToTheWholeWindowWhenRedisReportsNoTtl() {
        stubScript(List.of(11L, -1L));

        assertThatThrownBy(() -> service.check("bucket", "subject", RULE))
                .isInstanceOfSatisfying(RateLimitExceededException.class,
                        e -> assertThat(e.getRetryAfterSeconds()).isEqualTo(120));
    }

    @Test
    void failsOpenWhenRedisIsDown() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new RedisConnectionFailureException("redis down"));

        assertThatCode(() -> service.check("bucket", "subject", RULE)).doesNotThrowAnyException();
    }

    @Test
    void aNonPositiveLimitDisablesTheRuleWithoutTouchingRedis() {
        service.check("bucket", "subject", new Rule(0, Duration.ofMinutes(1)));
        service.check("bucket", "subject", new Rule(-5, Duration.ofMinutes(1)));
        service.check("bucket", "subject", null);

        verifyNoInteractions(redisTemplate);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stubScript(List<Long> result) {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn((List) result);
    }
}
