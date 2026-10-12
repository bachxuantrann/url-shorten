package com.auradev.url_shortener;

import com.auradev.url_shortener.support.ApiClient;
import com.auradev.url_shortener.support.ApiClient.Resp;
import com.auradev.url_shortener.support.TestcontainersConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Trang "Giám sát": backend gọi OpenObserve (ở đây là một máy chủ giả trong test, ghi lại mọi request) và trả log cho admin.
 * Kiểm tra quyền truy cập, câu SQL gửi đi (không bị chèn mã), xác thực Basic, phân trang, lỗi từ OpenObserve và việc mật khẩu
 * không bao giờ lọt ra phản hồi.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminMonitoringIntegrationTest {

    private static final String EMAIL = "monitor@test.local";
    private static final String PASSWORD = "S3cret-Monitor-Pw";
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Một request tới OpenObserve giả. */
    private record Seen(String path, String authorization, JsonNode body) {
        String sql() {
            return body == null ? "" : body.path("query").path("sql").asText();
        }
    }

    private static HttpServer fake;
    private static final List<Seen> SEEN = new CopyOnWriteArrayList<>();
    private static final AtomicInteger TOTAL = new AtomicInteger(120);
    private static final AtomicInteger SEARCH_STATUS = new AtomicInteger(200);
    private static final AtomicBoolean EXCEPTION_QUERY_FAILS = new AtomicBoolean(false);

    @BeforeAll
    static void startFake() throws IOException {
        fake = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fake.createContext("/healthz", ex -> reply(ex, 200, "{\"status\":\"ok\"}"));
        fake.createContext("/api/default/_search", ex -> {
            JsonNode body = JSON.readTree(ex.getRequestBody().readAllBytes());
            Seen seen = new Seen(ex.getRequestURI().toString(), ex.getRequestHeaders().getFirst("Authorization"), body);
            SEEN.add(seen);
            if (SEARCH_STATUS.get() != 200) {
                reply(ex, SEARCH_STATUS.get(), "{\"code\":500,\"message\":\"boom with secret internals\"}");
                return;
            }
            String sql = seen.sql();
            if (sql.contains("GROUP BY severity")) {
                reply(ex, 200, "{\"hits\":[{\"severity\":\"ERROR\",\"n\":3},{\"severity\":\"WARN\",\"n\":12},{\"severity\":\"INFO\",\"n\":500}]}");
            } else if (sql.contains("GROUP BY exception_type")) {
                if (EXCEPTION_QUERY_FAILS.get()) {
                    reply(ex, 400, "{\"code\":400,\"message\":\"no such column exception_type\"}");
                } else {
                    reply(ex, 200, "{\"hits\":[{\"exception_type\":\"java.lang.IllegalStateException\",\"n\":2},{\"exception_type\":\"java.net.ConnectException\",\"n\":1}]}");
                }
            } else if (sql.contains("count(*) AS n")) {
                reply(ex, 200, "{\"hits\":[{\"n\":" + TOTAL.get() + "}]}");
            } else {
                reply(ex, 200, "{\"hits\":[{\"_timestamp\":1791823374666818,\"severity\":\"ERROR\","
                        + "\"instrumentation_library_name\":\"com.auradev.url_shortener.service.impl.Foo\","
                        + "\"body\":\"Something failed\",\"trace_id\":\"0af7651916cd43dd8448eb211c80319c\","
                        + "\"exception_type\":\"java.lang.IllegalStateException\",\"exception_message\":\"bad state\","
                        + "\"exception_stacktrace\":\"" + "x".repeat(9000) + "\"},"
                        + "{\"_timestamp\":1791823374000000,\"severity\":\"WARN\",\"body\":\"Just a warning\"}]}");
            }
        });
        fake.start();
    }

    @AfterAll
    static void stopFake() {
        fake.stop(0);
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws IOException {
        // Cổng của máy chủ giả phải biết trước khi context khởi động, nên dựng nó ở đây nếu chưa có
        if (fake == null) {
            startFake();
        }
        registry.add("app.openobserve.url", () -> "http://127.0.0.1:" + fake.getAddress().getPort());
        registry.add("app.openobserve.ui-url", () -> "http://localhost:5080");
        registry.add("app.openobserve.email", () -> EMAIL);
        registry.add("app.openobserve.password", () -> PASSWORD);
    }

    @LocalServerPort
    private int port;

    private ApiClient api;
    private String admin;
    private String user;

    @BeforeEach
    void setUp() throws Exception {
        api = new ApiClient(port);
        admin = api.loginAsAdmin();
        user = api.registerAndLogin();
        SEEN.clear();
        TOTAL.set(120);
        SEARCH_STATUS.set(200);
        EXCEPTION_QUERY_FAILS.set(false);
    }

    private static void reply(com.sun.net.httpserver.HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    // ===========================
    //  QUYỀN TRUY CẬP
    // ===========================

    @Test
    void onlyAdminsCanUseMonitoring() throws Exception {
        for (String path : new String[]{"/status", "/summary", "/logs"}) {
            assertThat(api.call("GET", "/api/v1/admin/monitoring" + path, null, null).status()).as(path).isEqualTo(401);
            assertThat(api.call("GET", "/api/v1/admin/monitoring" + path, user, null).status()).as(path).isEqualTo(403);
        }
        assertThat(SEEN).as("nothing reaches OpenObserve without an admin").isEmpty();
    }

    // ===========================
    //  TRẠNG THÁI
    // ===========================

    @Test
    void statusReportsConfiguredReachableAndTheUiAddressWithoutCredentials() throws Exception {
        Resp resp = api.call("GET", "/api/v1/admin/monitoring/status", admin, null);

        assertThat(resp.status()).isEqualTo(200);
        JsonNode data = resp.body().get("data");
        assertThat(data.get("configured").asBoolean()).isTrue();
        assertThat(data.get("reachable").asBoolean()).isTrue();
        assertThat(data.get("uiUrl").asText()).isEqualTo("http://localhost:5080");
        assertThat(resp.body().toString()).doesNotContain(PASSWORD).doesNotContain(EMAIL);
    }

    // ===========================
    //  LOG
    // ===========================

    @Test
    void logsDefaultToErrorsAndWarningsAndAreMappedWithTruncation() throws Exception {
        Resp resp = api.call("GET", "/api/v1/admin/monitoring/logs", admin, null);

        assertThat(resp.status()).isEqualTo(200);
        JsonNode page = resp.body().get("data");
        assertThat(page.get("totalElements").asLong()).isEqualTo(120);
        JsonNode first = page.get("content").get(0);
        assertThat(first.get("level").asText()).isEqualTo("ERROR");
        assertThat(first.get("message").asText()).isEqualTo("Something failed");
        assertThat(first.get("logger").asText()).endsWith(".Foo");
        assertThat(first.get("traceId").asText()).isEqualTo("0af7651916cd43dd8448eb211c80319c");
        assertThat(first.get("exceptionType").asText()).isEqualTo("java.lang.IllegalStateException");
        assertThat(first.get("exceptionStacktrace").asText()).hasSize(8_001).endsWith("…"); // 8000 ký tự + dấu cắt
        assertThat(first.get("timestamp").asText()).isNotBlank();
        JsonNode second = page.get("content").get(1);
        assertThat(second.has("traceId")).isFalse(); // trường thiếu thì bỏ qua, không trả chuỗi rỗng
        // Câu SQL: chỉ ERROR và WARN, mới nhất trước
        Seen select = SEEN.stream().filter(s -> s.sql().contains("ORDER BY _timestamp DESC")).findFirst().orElseThrow();
        assertThat(select.sql()).contains("severity IN ('ERROR', 'WARN')").doesNotContain("str_match");
    }

    @Test
    void logsAuthenticateWithBasicAuthAndSearchTheRequestedWindow() throws Exception {
        api.call("GET", "/api/v1/admin/monitoring/logs?minutes=15&levels=INFO", admin, null);

        Seen seen = SEEN.get(0);
        assertThat(seen.authorization()).isEqualTo("Basic " + Base64.getEncoder().encodeToString((EMAIL + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8)));
        assertThat(seen.path()).isEqualTo("/api/default/_search?type=logs");
        long window = seen.body().path("query").path("end_time").asLong() - seen.body().path("query").path("start_time").asLong();
        assertThat(window).isEqualTo(15L * 60 * 1_000_000); // micro giây
        assertThat(seen.sql()).contains("severity IN ('INFO')");
    }

    @Test
    void pagingMapsToOffsetAndSizeAndSizeIsCapped() throws Exception {
        api.call("GET", "/api/v1/admin/monitoring/logs?page=2&size=10", admin, null);
        api.call("GET", "/api/v1/admin/monitoring/logs?size=100000", admin, null);

        List<Seen> selects = SEEN.stream().filter(s -> s.sql().contains("ORDER BY")).toList();
        assertThat(selects.get(0).body().path("query").path("from").asInt()).isEqualTo(20);
        assertThat(selects.get(0).body().path("query").path("size").asInt()).isEqualTo(10);
        assertThat(selects.get(1).body().path("query").path("size").asInt()).isEqualTo(100);
    }

    @Test
    void noMatchingRowsSkipsTheSecondQuery() throws Exception {
        TOTAL.set(0);

        Resp resp = api.call("GET", "/api/v1/admin/monitoring/logs", admin, null);

        assertThat(resp.body().get("data").get("content")).isEmpty();
        assertThat(SEEN).hasSize(1); // chỉ câu đếm
    }

    @Test
    void keywordsAreEscapedAndLevelsAreWhitelistedSoNoSqlCanBeInjected() throws Exception {
        String keyword = java.net.URLEncoder.encode("it's' OR 1=1 --", StandardCharsets.UTF_8);
        assertThat(api.call("GET", "/api/v1/admin/monitoring/logs?keyword=" + keyword, admin, null).status()).isEqualTo(200);

        assertThat(SEEN.get(0).sql()).contains("str_match_ignore_case(body, 'it''s'' OR 1=1 --')");

        SEEN.clear();
        String evilLevel = java.net.URLEncoder.encode("ERROR') OR ('1'='1", StandardCharsets.UTF_8);
        assertThat(api.call("GET", "/api/v1/admin/monitoring/logs?levels=" + evilLevel, admin, null).status()).isEqualTo(400);
        assertThat(api.call("GET", "/api/v1/admin/monitoring/logs?levels=NOPE", admin, null).status()).isEqualTo(400);
        assertThat(SEEN).as("rejected before reaching OpenObserve").isEmpty();
    }

    @Test
    void windowsOutsideTheAllowedListAndTooLongKeywordsAreRejected() throws Exception {
        assertThat(api.call("GET", "/api/v1/admin/monitoring/logs?minutes=7", admin, null).status()).isEqualTo(400);
        assertThat(api.call("GET", "/api/v1/admin/monitoring/summary?minutes=99999", admin, null).status()).isEqualTo(400);
        assertThat(api.call("GET", "/api/v1/admin/monitoring/logs?keyword=" + "x".repeat(101), admin, null).status()).isEqualTo(400);
        assertThat(SEEN).isEmpty();
    }

    // ===========================
    //  TÓM TẮT
    // ===========================

    @Test
    void summaryCountsEveryLevelAndListsTopExceptions() throws Exception {
        Resp resp = api.call("GET", "/api/v1/admin/monitoring/summary?minutes=360", admin, null);

        assertThat(resp.status()).isEqualTo(200);
        JsonNode data = resp.body().get("data");
        assertThat(data.get("minutes").asInt()).isEqualTo(360);
        assertThat(data.get("levels").get("ERROR").asLong()).isEqualTo(3);
        assertThat(data.get("levels").get("WARN").asLong()).isEqualTo(12);
        assertThat(data.get("levels").get("INFO").asLong()).isEqualTo(500);
        assertThat(data.get("levels").get("DEBUG").asLong()).isZero(); // mức không có dòng nào vẫn có mặt, bằng 0
        assertThat(data.get("topExceptions").get(0).get("type").asText()).isEqualTo("java.lang.IllegalStateException");
        assertThat(data.get("topExceptions").get(0).get("count").asLong()).isEqualTo(2);
    }

    @Test
    void summaryStillWorksWhenOpenObserveHasNoExceptionColumnYet() throws Exception {
        EXCEPTION_QUERY_FAILS.set(true);

        Resp resp = api.call("GET", "/api/v1/admin/monitoring/summary", admin, null);

        assertThat(resp.status()).isEqualTo(200);
        assertThat(resp.body().get("data").get("topExceptions")).isEmpty();
        assertThat(resp.body().get("data").get("levels").get("ERROR").asLong()).isEqualTo(3);
    }

    // ===========================
    //  LỖI TỪ OPENOBSERVE
    // ===========================

    @Test
    void openObserveFailuresBecomeABadGatewayWithoutLeakingInternals() throws Exception {
        SEARCH_STATUS.set(500);

        Resp resp = api.call("GET", "/api/v1/admin/monitoring/logs", admin, null);

        assertThat(resp.status()).isEqualTo(502);
        assertThat(resp.code()).isEqualTo("ERR_601");
        assertThat(resp.body().toString()).doesNotContain("secret internals").doesNotContain(PASSWORD);
        assertThat(api.call("GET", "/api/v1/admin/monitoring/summary", admin, null).status()).isEqualTo(502);
    }
}
