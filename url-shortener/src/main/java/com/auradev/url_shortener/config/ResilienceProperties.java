package com.auradev.url_shortener.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Cấu hình circuit breaker, bind từ {@code application-*.yaml} (prefix {@code app.resilience}).
 *
 * <pre>
 * app:
 *   resilience:
 *     redis-cache:  { sliding-window-size: 20, minimum-number-of-calls: 10, failure-rate-threshold: 50, wait-duration-in-open-state: PT10S }
 *     redis-rate-limit: ...
 *     kafka-publish: ...
 * </pre>
 *
 * Breaker mở khi tỉ lệ lỗi trong cửa sổ {@code sliding-window-size} lần gọi gần nhất vượt ngưỡng (và có ít
 * nhất {@code minimum-number-of-calls} lần). Khi mở, các lần gọi bị bỏ qua ngay (không chờ timeout) cho tới
 * hết {@code wait-duration-in-open-state}, rồi cho {@code permitted-calls-in-half-open} lần thăm dò.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.resilience")
public class ResilienceProperties {

    /** Đọc/ghi cache redirect. Mở thì redirect đi thẳng Postgres, không còn mất thời gian chờ Redis. */
    private Breaker redisCache = new Breaker();

    /** Đếm rate limit. Mở thì bỏ qua giới hạn (fail-open như khi Redis lỗi). */
    private Breaker redisRateLimit = new Breaker();

    /** Gửi click event lên Kafka. Mở thì bỏ event ngay để các thread gửi không bị kẹt chờ broker. */
    private Breaker kafkaPublish = new Breaker();

    @Getter
    @Setter
    public static class Breaker {
        private int slidingWindowSize = 20;
        private int minimumNumberOfCalls = 10;
        private float failureRateThreshold = 50f;
        private Duration waitDurationInOpenState = Duration.ofSeconds(10);
        private int permittedCallsInHalfOpen = 3;
    }
}
