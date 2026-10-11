package com.auradev.url_shortener;

import com.auradev.url_shortener.support.ApiClient;
import com.auradev.url_shortener.support.ApiClient.Resp;
import com.auradev.url_shortener.support.TestcontainersConfiguration;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Kiểm tra toàn bộ đường quan sát chạy thật: trace (HTTP, Redis, JDBC, Kafka producer + consumer trong cùng
 * một trace), metric tuỳ biến, và dữ liệu thực sự rời ứng dụng theo OTLP tới một collector giả (không cần backend thật).
 */
@Import({TestcontainersConfiguration.class, ObservabilityIntegrationTest.TestSpans.class})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.tracing.sampling.probability=1.0",
        "management.tracing.export.otlp.enabled=true",
        "management.logging.export.otlp.enabled=true",
        "management.otlp.metrics.export.enabled=true",
        "management.otlp.metrics.export.step=1s",
        "management.opentelemetry.tracing.export.schedule-delay=100ms",
        "management.opentelemetry.logging.export.schedule-delay=100ms",
        "management.opentelemetry.resource-attributes[service.name]=url-shortener-test",
        // Backend có xác thực (OpenObserve) cần header Authorization trên cả ba loại tín hiệu
        "management.opentelemetry.tracing.export.otlp.headers.Authorization=Basic dGVzdDp0ZXN0",
        "management.opentelemetry.logging.export.otlp.headers.Authorization=Basic dGVzdDp0ZXN0",
        "management.otlp.metrics.export.headers.Authorization=Basic dGVzdDp0ZXN0"
})
class ObservabilityIntegrationTest {

    /** Collector OTLP/HTTP giả: ghi lại body của mọi POST theo đường dẫn. */
    private static final HttpServer COLLECTOR;
    private static final Map<String, List<byte[]>> RECEIVED = new ConcurrentHashMap<>();
    private static final Map<String, List<String>> AUTHORIZATION = new ConcurrentHashMap<>();

    static {
        try {
            COLLECTOR = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        COLLECTOR.createContext("/", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            RECEIVED.computeIfAbsent(exchange.getRequestURI().getPath(), k -> new CopyOnWriteArrayList<>()).add(body);
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            AUTHORIZATION.computeIfAbsent(exchange.getRequestURI().getPath(), k -> new CopyOnWriteArrayList<>())
                    .add(auth == null ? "" : auth);
            exchange.getResponseHeaders().add("Content-Type", "application/x-protobuf");
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        COLLECTOR.start();
    }

    @DynamicPropertySource
    static void otlpEndpoints(DynamicPropertyRegistry registry) {
        String base = "http://127.0.0.1:" + COLLECTOR.getAddress().getPort();
        registry.add("management.opentelemetry.tracing.export.otlp.endpoint", () -> base + "/v1/traces");
        registry.add("management.opentelemetry.logging.export.otlp.endpoint", () -> base + "/v1/logs");
        registry.add("management.otlp.metrics.export.url", () -> base + "/v1/metrics");
    }

    @AfterAll
    static void stopCollector() {
        COLLECTOR.stop(0);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestSpans {
        /** Là một SpanExporter nên Boot gom vào cùng pipeline với exporter OTLP. */
        @Bean
        InMemorySpanExporter inMemorySpanExporter() {
            return InMemorySpanExporter.create();
        }
    }

    @LocalServerPort
    private int port;

    @Autowired private InMemorySpanExporter spans;
    @Autowired private MeterRegistry        meters;

    private ApiClient api;

    @BeforeEach
    void setUp() {
        api = new ApiClient(port);
        spans.reset();
    }

    // ===========================
    //  TRACE
    // ===========================

    @Test
    void oneRedirectProducesHttpRedisJdbcAndKafkaSpansInASingleTrace() throws Exception {
        String owner = api.registerAndLogin();
        String code = api.createUrl(owner, "https://example.com/trace-me");
        spans.reset();

        Resp resp = api.call("GET", "/" + code, null, null, Map.of("X-Forwarded-For", "198.51.100.77"));
        assertThat(resp.status()).isEqualTo(302);

        // Span của consumer được xuất theo lô nên chờ tới khi nó xuất hiện
        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(spans.getFinishedSpanItems()).anyMatch(s -> s.getName().equals("click.ingest")));
        List<SpanData> all = spans.getFinishedSpanItems();

        SpanData http = all.stream().filter(s -> s.getKind().name().equals("SERVER")
                && s.getName().startsWith("http get")).findFirst().orElseThrow();
        String traceId = http.getTraceId();
        List<SpanData> inTrace = all.stream().filter(s -> s.getTraceId().equals(traceId)).toList();

        // Cùng một trace với request: Redis (cache), JDBC (đọc link) và việc gửi Kafka (qua thread khác)
        assertThat(inTrace).anyMatch(s -> "redis".equals(attr(s, "db.system")));
        assertThat(inTrace).anyMatch(s -> attr(s, "jdbc.datasource.driver") != null || attr(s, "jdbc.query[0]") != null);
        assertThat(inTrace).anyMatch(s -> s.getKind().name().equals("PRODUCER") && s.getName().contains("url-clicks"));

        // Consumer: span riêng cho cả lô, có span link về trace của request, và các span JDBC của việc ghi nằm dưới nó
        SpanData ingest = all.stream().filter(s -> s.getName().equals("click.ingest")).findFirst().orElseThrow();
        assertThat(ingest.getKind().name()).isEqualTo("CONSUMER");
        assertThat(ingest.getLinks()).anyMatch(link -> link.getSpanContext().getTraceId().equals(traceId));
        assertThat(all).filteredOn(s -> s.getTraceId().equals(ingest.getTraceId()))
                .anyMatch(s -> attr(s, "jdbc.query[0]") != null && s.getParentSpanId() != null);
    }

    @Test
    void healthChecksDoNotCreateSpans() throws Exception {
        api.call("GET", "/actuator/health", null, null);
        Thread.sleep(500);

        assertThat(spans.getFinishedSpanItems()).noneMatch(s -> s.getName().contains("actuator"));
    }

    // ===========================
    //  METRIC
    // ===========================

    @Test
    void customMetricsAreRecorded() throws Exception {
        String owner = api.registerAndLogin();
        String code = api.createUrl(owner, "https://example.com/metrics-me");

        api.call("GET", "/" + code, null, null, Map.of("X-Forwarded-For", "198.51.100.78"));
        api.call("GET", "/" + code, null, null, Map.of("X-Forwarded-For", "198.51.100.78"));
        api.call("GET", "/nosuchcode" + code, null, null, Map.of("X-Forwarded-For", "198.51.100.78"));

        assertThat(count("redirect.latency", "outcome", "redirect")).isGreaterThanOrEqualTo(2);
        assertThat(count("redirect.latency", "outcome", "url_not_found")).isGreaterThanOrEqualTo(1);
        assertThat(counter("url.cache.requests", "result", "hit")).isGreaterThanOrEqualTo(1);
        assertThat(counter("url.cache.requests", "result", "miss")).isGreaterThanOrEqualTo(1);

        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(counter("click.events", "result", "published")).isGreaterThanOrEqualTo(2);
            assertThat(meters.find("click.ingest.inserted").counter().count()).isGreaterThanOrEqualTo(2);
            assertThat(meters.find("click.ingest.batch.size").summary().count()).isGreaterThanOrEqualTo(1);
        });
    }

    @Test
    void circuitBreakersAndKafkaClientMetricsAreExported() {
        for (String name : List.of("redisCache", "redisRateLimit", "kafkaPublish")) {
            assertThat(meters.find("resilience4j.circuitbreaker.state").tag("name", name).gauges())
                    .as("breaker %s state gauge", name).isNotEmpty();
        }
        List<String> names = meters.getMeters().stream().map(m -> m.getId().getName()).distinct().sorted().toList();
        System.out.println("KAFKA METERS: " + names.stream().filter(n -> n.startsWith("kafka")).collect(Collectors.joining(", ")));
        assertThat(names).anyMatch(n -> n.startsWith("kafka.consumer"));
    }

    // ===========================
    //  OTLP: dữ liệu thật sự rời ứng dụng
    // ===========================

    @Test
    void traceMetricAndLogDataReachTheOtlpCollector() throws Exception {
        String owner = api.registerAndLogin();
        String code = api.createUrl(owner, "https://example.com/otlp-me");
        api.call("GET", "/" + code, null, null, Map.of("X-Forwarded-For", "198.51.100.79"));

        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(bodies("/v1/traces")).as("traces").isNotEmpty();
            assertThat(bodies("/v1/metrics")).as("metrics").isNotEmpty();
            assertThat(bodies("/v1/logs")).as("logs").isNotEmpty();
        });
        // service.name nằm trong resource của cả ba loại dữ liệu
        assertThat(String.join("", bodies("/v1/traces"))).contains("url-shortener-test");
        assertThat(String.join("", bodies("/v1/metrics"))).contains("url-shortener-test");
        assertThat(String.join("", bodies("/v1/logs"))).contains("url-shortener-test");
        assertThat(String.join("", bodies("/v1/metrics"))).contains("redirect.latency");
    }

    @Test
    void theAuthorizationHeaderIsSentOnEverySignal() throws Exception {
        String owner = api.registerAndLogin();
        String code = api.createUrl(owner, "https://example.com/auth-header");
        api.call("GET", "/" + code, null, null, Map.of("X-Forwarded-For", "198.51.100.80"));

        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() -> {
            for (String path : List.of("/v1/traces", "/v1/metrics", "/v1/logs")) {
                assertThat(AUTHORIZATION.getOrDefault(path, List.of())).as(path).isNotEmpty();
                assertThat(AUTHORIZATION.get(path)).as(path).allMatch("Basic dGVzdDp0ZXN0"::equals);
            }
        });
    }

    // ===========================
    //  HELPERS
    // ===========================

    private static List<String> bodies(String path) {
        return RECEIVED.getOrDefault(path, List.of()).stream()
                .map(b -> new String(b, StandardCharsets.ISO_8859_1)).toList();
    }

    private static String attr(SpanData span, String key) {
        return span.getAttributes().asMap().entrySet().stream()
                .filter(e -> e.getKey().getKey().equals(key)).map(e -> String.valueOf(e.getValue())).findFirst().orElse(null);
    }

    private long count(String timer, String tag, String value) {
        var t = meters.find(timer).tag(tag, value).timer();
        return t == null ? 0 : t.count();
    }

    private double counter(String name, String tag, String value) {
        var c = meters.find(name).tag(tag, value).counter();
        return c == null ? 0 : c.count();
    }
}
