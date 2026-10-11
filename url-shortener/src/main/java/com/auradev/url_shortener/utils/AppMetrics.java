package com.auradev.url_shortener.utils;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Các metric nghiệp vụ tuỳ biến (xuất qua OTLP cùng metric của Boot). Tập trung tên metric và tag ở đây
 * để dashboard và code không lệch nhau.
 *
 * <ul>
 *   <li>{@code redirect.latency} (timer, tag {@code outcome}) — độ trễ của {@code GET /{code}}, có histogram
 *       để tính p99; {@code outcome} = redirect | url_not_found | url_gone | url_access_blocked | url_password_required</li>
 *   <li>{@code url.cache.requests} (counter, tag {@code result}) — hit | miss | negative_hit | corrupt | error |
 *       circuit_open. Tỉ lệ hit = hit / (hit + miss + error + circuit_open)</li>
 *   <li>{@code click.events} (counter, tag {@code result}) — published | dropped | failed | skipped_circuit_open
 *       | dead_letter (bản tin hỏng ở consumer)</li>
 *   <li>{@code click.ingest.batch.size} (summary) và {@code click.ingest.inserted} (counter) — consumer</li>
 *   <li>{@code resilience4j.circuitbreaker.*} — trạng thái các circuit breaker (xem {@code ResilienceConfig})</li>
 *   <li>{@code kafka.consumer.*} — metric của client Kafka, gồm {@code records.lag.max} (độ trễ consumer)</li>
 * </ul>
 */
@Component
public class AppMetrics {

    private final MeterRegistry registry;

    public AppMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public Timer.Sample startTimer() {
        return Timer.start(registry);
    }

    public void recordRedirect(Timer.Sample sample, String outcome) {
        sample.stop(Timer.builder("redirect.latency")
                .description("Latency of the public redirect endpoint")
                .tag("outcome", outcome)
                .publishPercentileHistogram()
                .minimumExpectedValue(Duration.ofMillis(1))
                .maximumExpectedValue(Duration.ofSeconds(5))
                .register(registry));
    }

    public void cache(String result) {
        registry.counter("url.cache.requests", "result", result).increment();
    }

    public void click(String result) {
        registry.counter("click.events", "result", result).increment();
    }

    public void ingestBatch(int received, int inserted) {
        DistributionSummary.builder("click.ingest.batch.size")
                .description("Click events per consumed batch")
                .register(registry)
                .record(received);
        registry.counter("click.ingest.inserted").increment(inserted);
    }
}
