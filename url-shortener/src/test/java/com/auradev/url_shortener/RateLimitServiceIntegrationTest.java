package com.auradev.url_shortener;

import com.auradev.url_shortener.config.RateLimitProperties.Rule;
import com.auradev.url_shortener.exception.RateLimitExceededException;
import com.auradev.url_shortener.support.TestcontainersConfiguration;
import com.auradev.url_shortener.utils.RateLimitService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link RateLimitService} với Redis thật (script Lua, TTL, cửa sổ, đồng thời).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class RateLimitServiceIntegrationTest {

    @Autowired
    private RateLimitService rateLimitService;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @Test
    void allowsUpToTheLimitThenRejectsWithARetryAfterInsideTheWindow() {
        String bucket = bucket();
        Rule rule = new Rule(3, Duration.ofMinutes(10));

        for (int i = 0; i < 3; i++) {
            rateLimitService.check(bucket, "client", rule);
        }

        assertThatThrownBy(() -> rateLimitService.check(bucket, "client", rule))
                .isInstanceOfSatisfying(RateLimitExceededException.class,
                        e -> assertThat(e.getRetryAfterSeconds()).isBetween(1L, 600L));
    }

    @Test
    void subjectsAreCountedIndependently() {
        String bucket = bucket();
        Rule rule = new Rule(1, Duration.ofMinutes(10));

        rateLimitService.check(bucket, "alice", rule);
        assertThatCode(() -> rateLimitService.check(bucket, "bob", rule)).doesNotThrowAnyException();
        assertThatThrownBy(() -> rateLimitService.check(bucket, "alice", rule))
                .isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    void bucketsAreCountedIndependently() {
        Rule rule = new Rule(1, Duration.ofMinutes(10));
        String subject = UUID.randomUUID().toString();

        rateLimitService.check(bucket(), subject, rule);

        assertThatCode(() -> rateLimitService.check(bucket(), subject, rule)).doesNotThrowAnyException();
    }

    @Test
    void theCounterKeyAlwaysCarriesATtl() {
        String bucket = bucket();

        rateLimitService.check(bucket, "client", new Rule(5, Duration.ofMinutes(10)));

        assertThat(redisTemplate.getExpire("RL:" + bucket + ":client")).isBetween(1L, 600L);
    }

    @Test
    void theWindowResetsAfterItExpires() throws Exception {
        String bucket = bucket();
        Rule rule = new Rule(2, Duration.ofSeconds(2));

        rateLimitService.check(bucket, "client", rule);
        rateLimitService.check(bucket, "client", rule);
        assertThatThrownBy(() -> rateLimitService.check(bucket, "client", rule))
                .isInstanceOf(RateLimitExceededException.class);

        Thread.sleep(2300);

        assertThatCode(() -> rateLimitService.check(bucket, "client", rule)).doesNotThrowAnyException();
    }

    @Test
    void concurrentRequestsNeverLetMoreThanTheLimitThrough() throws Exception {
        String bucket = bucket();
        Rule rule = new Rule(20, Duration.ofMinutes(10));
        int attempts = 100;

        ExecutorService pool = Executors.newFixedThreadPool(10);
        List<Future<Boolean>> results = new ArrayList<>();
        try {
            for (int i = 0; i < attempts; i++) {
                Callable<Boolean> attempt = () -> {
                    try {
                        rateLimitService.check(bucket, "shared", rule);
                        return true;
                    } catch (RateLimitExceededException e) {
                        return false;
                    }
                };
                results.add(pool.submit(attempt));
            }
            long allowed = 0;
            for (Future<Boolean> result : results) {
                if (result.get()) {
                    allowed++;
                }
            }
            assertThat(allowed).isEqualTo(20);
        } finally {
            pool.shutdownNow();
        }
    }

    private String bucket() {
        return "test-" + UUID.randomUUID();
    }
}
