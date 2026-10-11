package com.auradev.url_shortener.service.impl;

import com.auradev.url_shortener.config.ClickProperties;
import com.auradev.url_shortener.event.ClickEvent;
import com.auradev.url_shortener.service.ClickPublisher;
import com.auradev.url_shortener.service.GeoIpService;
import com.auradev.url_shortener.config.ResilienceConfig;
import com.auradev.url_shortener.utils.AppMetrics;
import com.auradev.url_shortener.utils.ClientIpUtils;
import com.auradev.url_shortener.utils.VisitorHasher;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/**
 * Gửi {@link ClickEvent} lên Kafka mà không bao giờ làm chậm hay làm hỏng redirect.
 *
 * <p>Thread request chỉ đọc IP/User-Agent/header, băm danh tính rồi giao việc cho một executor có
 * hàng đợi giới hạn ({@code KafkaConfig#clickPublishExecutor}). {@code KafkaTemplate.send} có thể chặn
 * tới {@code max.block.ms} khi broker chết nên không được gọi trên thread request. Hàng đợi đầy thì
 * event bị bỏ và đếm vào {@link #droppedCount()}; gửi thất bại đếm vào {@link #failedCount()}.
 *
 * <p>Circuit breaker {@code kafkaPublish}: broker chết thì mỗi lần gửi tốn tới {@code max.block.ms} của một
 * thread; sau một loạt lỗi breaker mở và event bị bỏ ngay ({@link #skippedCount()}) để các thread không bị kẹt
 * và hàng đợi không đầy vô ích. Sau thời gian chờ breaker cho thăm dò vài lần để tự đóng lại khi broker sống.
 *
 * <p>Request <b>HEAD</b> (health check, trình xem trước link) không được tính.
 */
@Slf4j
@Service
public class KafkaClickPublisher implements ClickPublisher {

    private static final int MAX_USER_AGENT_LENGTH = 256;

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper                  objectMapper;
    private final ClickProperties               properties;
    private final VisitorHasher                 visitorHasher;
    private final GeoIpService                  geoIpService;
    private final Clock                         clock;
    private final Executor                      executor;
    private final CircuitBreaker                breaker;
    private final AppMetrics                    metrics;

    private final LongAdder dropped = new LongAdder();
    private final LongAdder failed  = new LongAdder();
    private final LongAdder skipped = new LongAdder();

    public KafkaClickPublisher(
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            ClickProperties properties,
            VisitorHasher visitorHasher,
            GeoIpService geoIpService,
            Clock clock,
            @Qualifier("clickPublishExecutor") Executor executor,
            @Qualifier(ResilienceConfig.KAFKA_PUBLISH) CircuitBreaker breaker,
            AppMetrics metrics
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper  = objectMapper;
        this.properties    = properties;
        this.visitorHasher = visitorHasher;
        this.geoIpService  = geoIpService;
        this.clock         = clock;
        this.executor      = executor;
        this.breaker       = breaker;
        this.metrics       = metrics;
    }

    @Override
    public void publish(String shortCode, HttpServletRequest request) {
        try {
            if ("HEAD".equalsIgnoreCase(request.getMethod())) {
                return;
            }
            ClickEvent event = buildEvent(shortCode, request);
            executor.execute(() -> send(event));
        } catch (RejectedExecutionException e) {
            dropped.increment();
            metrics.click("dropped");
            log.warn("Click event dropped, publish queue is full (code={})", shortCode);
        } catch (RuntimeException e) {
            failed.increment();
            metrics.click("failed");
            log.warn("Could not create click event (code={})", shortCode, e);
        }
    }

    /** Số event bị bỏ vì hàng đợi gửi đã đầy. */
    public long droppedCount() {
        return dropped.sum();
    }

    /** Số event gửi thất bại (broker chết, timeout, lỗi serialize...). */
    public long failedCount() {
        return failed.sum();
    }

    // ===========================
    //  PRIVATE HELPERS
    // ===========================

    private ClickEvent buildEvent(String shortCode, HttpServletRequest request) {
        LocalDateTime now = LocalDateTime.now(clock);
        String userAgent = truncate(request.getHeader(HttpHeaders.USER_AGENT), MAX_USER_AGENT_LENGTH);
        String ip = ClientIpUtils.resolve(request);

        return new ClickEvent(
                UUID.randomUUID().toString(),
                shortCode,
                now,
                visitorHasher.hash(ip, userAgent, now.toLocalDate()),
                geoIpService.countryOf(ip),
                userAgent);
    }

    /** Số event bị bỏ vì circuit breaker đang mở. */
    public long skippedCount() {
        return skipped.sum();
    }

    private void send(ClickEvent event) {
        if (!breaker.tryAcquirePermission()) {
            skipped.increment();
            metrics.click("skipped_circuit_open");
            return;
        }
        long start = System.nanoTime();
        try {
            String json = objectMapper.writeValueAsString(event);
            kafkaTemplate.send(properties.getTopic(), event.shortCode(), json).whenComplete((result, error) -> {
                long elapsed = System.nanoTime() - start;
                if (error != null) {
                    breaker.onError(elapsed, TimeUnit.NANOSECONDS, error);
                    failed.increment();
                    metrics.click("failed");
                    log.warn("Click event not delivered (code={}, eventId={}): {}",
                            event.shortCode(), event.eventId(), error.toString());
                } else {
                    breaker.onSuccess(elapsed, TimeUnit.NANOSECONDS);
                    metrics.click("published");
                }
            });
        } catch (Exception e) {
            // Mỗi permission đã lấy phải được trả bằng đúng một onSuccess/onError, kể cả khi send ném đồng bộ
            breaker.onError(System.nanoTime() - start, TimeUnit.NANOSECONDS, e);
            failed.increment();
            metrics.click("failed");
            log.warn("Click event not sent (code={}, eventId={}): {}", event.shortCode(), event.eventId(), e.toString());
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
