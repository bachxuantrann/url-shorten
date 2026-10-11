package com.auradev.url_shortener.config;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Circuit breaker cho các phụ thuộc có thể chết mà redirect vẫn phải chạy được: Redis (cache, rate limit)
 * và Kafka (click event). Dùng thư viện lõi Resilience4j lập trình trực tiếp (không cần AOP/starter).
 *
 * <p>Trạng thái được xuất thành metric {@code resilience4j.circuitbreaker.state} (tag {@code name}).
 */
@Configuration
public class ResilienceConfig {

    public static final String REDIS_CACHE = "redisCache";
    public static final String REDIS_RATE_LIMIT = "redisRateLimit";
    public static final String KAFKA_PUBLISH = "kafkaPublish";

    @Bean
    CircuitBreakerRegistry circuitBreakerRegistry(MeterRegistry meterRegistry) {
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry).bindTo(meterRegistry);
        return registry;
    }

    @Bean
    @Qualifier(REDIS_CACHE)
    CircuitBreaker redisCacheBreaker(CircuitBreakerRegistry registry, ResilienceProperties properties) {
        return registry.circuitBreaker(REDIS_CACHE, config(properties.getRedisCache()));
    }

    @Bean
    @Qualifier(REDIS_RATE_LIMIT)
    CircuitBreaker redisRateLimitBreaker(CircuitBreakerRegistry registry, ResilienceProperties properties) {
        return registry.circuitBreaker(REDIS_RATE_LIMIT, config(properties.getRedisRateLimit()));
    }

    @Bean
    @Qualifier(KAFKA_PUBLISH)
    CircuitBreaker kafkaPublishBreaker(CircuitBreakerRegistry registry, ResilienceProperties properties) {
        return registry.circuitBreaker(KAFKA_PUBLISH, config(properties.getKafkaPublish()));
    }

    static CircuitBreakerConfig config(ResilienceProperties.Breaker settings) {
        return CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(settings.getSlidingWindowSize())
                .minimumNumberOfCalls(settings.getMinimumNumberOfCalls())
                .failureRateThreshold(settings.getFailureRateThreshold())
                .waitDurationInOpenState(settings.getWaitDurationInOpenState())
                .permittedNumberOfCallsInHalfOpenState(settings.getPermittedCallsInHalfOpen())
                .build();
    }
}
