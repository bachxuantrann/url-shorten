package com.auradev.url_shortener;

import com.auradev.url_shortener.entity.AbuseReport;
import com.auradev.url_shortener.enums.AbuseReportStatusEnum;
import com.auradev.url_shortener.repository.AbuseReportRepository;
import com.auradev.url_shortener.repository.UrlRepository;
import com.auradev.url_shortener.support.ApiClient;
import com.auradev.url_shortener.support.ApiClient.Resp;
import com.auradev.url_shortener.support.TestcontainersConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Báo cáo lạm dụng link end-to-end qua HTTP thật: người lạ báo, admin xem gom theo link, đóng báo cáo,
 * chặn link thì báo cáo coi như đã xử lý, giới hạn tốc độ và quyền truy cập.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AbuseReportApiIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private AbuseReportRepository abuseReportRepository;

    @Autowired
    private UrlRepository urlRepository;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private ApiClient api;
    private String admin;
    private String owner;

    @BeforeEach
    void setUp() throws Exception {
        api = new ApiClient(port);
        admin = api.loginAsAdmin();
        owner = api.registerAndLogin();
    }

    // ===========================
    //  NGƯỜI LẠ BÁO CÁO
    // ===========================

    @Test
    void anyoneCanReportALinkWithoutLoggingIn() throws Exception {
        String code = newLink();

        Resp resp = report(code, "PHISHING", "Fake bank login page", uniqueIp());

        assertThat(resp.status()).isEqualTo(201);
        JsonNode summary = openSummary(code);
        assertThat(summary.get("reportCount").asInt()).isEqualTo(1);
        assertThat(summary.get("reasons").get("PHISHING").asInt()).isEqualTo(1);
        assertThat(summary.get("linkStatus").asText()).isEqualTo("ACTIVE");
        assertThat(summary.get("shortUrl").asText()).endsWith("/" + code);
    }

    @Test
    void sameReporterReportingTheSameLinkTwiceCountsOnce() throws Exception {
        String code = newLink();
        String ip = uniqueIp();

        assertThat(report(code, "SPAM", null, ip).status()).isEqualTo(201);
        // Lần hai vẫn thành công (không lộ việc đã báo) nhưng không tạo báo cáo mới, kể cả đổi lý do
        assertThat(report(code, "MALWARE", "again", ip).status()).isEqualTo(201);

        assertThat(openSummary(code).get("reportCount").asInt()).isEqualTo(1);
    }

    @Test
    void differentReportersAreCountedPerReason() throws Exception {
        String code = newLink();

        report(code, "SPAM", null, uniqueIp());
        report(code, "SPAM", null, uniqueIp());
        report(code, "MALWARE", "drive-by download", uniqueIp());

        JsonNode summary = openSummary(code);
        assertThat(summary.get("reportCount").asInt()).isEqualTo(3);
        assertThat(summary.get("reasons").get("SPAM").asInt()).isEqualTo(2);
        assertThat(summary.get("reasons").get("MALWARE").asInt()).isEqualTo(1);
    }

    @Test
    void rawIpIsNeverStored() throws Exception {
        String code = newLink();
        String ip = uniqueIp();
        report(code, "SPAM", null, ip);

        List<AbuseReport> stored = abuseReportRepository.findTop100ByUrlIdAndStatusOrderByCreatedAtDesc(
                urlRepository.findByShortCode(code).orElseThrow().getId(), AbuseReportStatusEnum.OPEN);

        assertThat(stored).hasSize(1);
        assertThat(stored.get(0).getReporterHash()).hasSize(32).isNotEqualTo(ip).doesNotContain(ip);
        assertThat(stored.get(0).getCreatedBy()).isEqualTo("anonymous");
    }

    @Test
    void invalidRequestsAreRejectedWith400() throws Exception {
        String code = newLink();

        assertThat(rawReport("{\"shortCode\":\"" + code + "\"}", uniqueIp()).status()).isEqualTo(400); // thiếu lý do
        assertThat(rawReport("{\"reason\":\"SPAM\"}", uniqueIp()).status()).isEqualTo(400);            // thiếu mã
        assertThat(rawReport("{\"shortCode\":\"" + code + "\",\"reason\":\"SPAM\",\"details\":\""
                + "x".repeat(501) + "\"}", uniqueIp()).status()).isEqualTo(400);
        // Giá trị enum lạ và JSON hỏng là lỗi của client (400), không phải 500
        assertThat(rawReport("{\"shortCode\":\"" + code + "\",\"reason\":\"NOT_A_REASON\"}", uniqueIp()).status())
                .isEqualTo(400);
        assertThat(rawReport("{not json", uniqueIp()).status()).isEqualTo(400);
        assertThat(openSummaries()).noneMatch(s -> s.get("shortCode").asText().equals(code));
    }

    @Test
    void unknownMalformedReservedAndDeletedCodesAreNotFound() throws Exception {
        String deleted = api.createUrl(owner, "https://example.com/" + UUID.randomUUID());
        assertThat(api.call("DELETE", "/api/v1/urls/" + deleted, owner, null).status()).isEqualTo(200);

        for (String code : new String[]{"nope1234", "ab", "admin", "bad code!", deleted}) {
            Resp resp = report(code, "SPAM", null, uniqueIp());
            assertThat(resp.status()).as(code).isEqualTo(404);
            assertThat(resp.code()).as(code).isEqualTo("ERR_400");
        }
    }

    @Test
    void reportingABlockedLinkSucceedsButStoresNothing() throws Exception {
        String code = newLink();
        assertThat(api.call("PUT", "/api/v1/admin/urls/" + code + "/block", admin, "{\"reason\":\"spam\"}").status())
                .isEqualTo(200);

        assertThat(report(code, "SPAM", null, uniqueIp()).status()).isEqualTo(201);

        assertThat(openSummaries()).noneMatch(s -> s.get("shortCode").asText().equals(code));
    }

    @Test
    void reportsAreRateLimitedPerIp() throws Exception {
        String code = newLink();
        String ip = uniqueIp();

        for (int i = 0; i < 5; i++) {
            assertThat(report(code, "SPAM", null, ip).status()).as("request %d", i).isEqualTo(201);
        }
        Resp limited = report(code, "SPAM", null, ip);

        assertThat(limited.status()).isEqualTo(429);
        assertThat(limited.code()).isEqualTo("ERR_500");
        assertThat(limited.header("Retry-After")).isNotNull();
        // IP khác không bị ảnh hưởng
        assertThat(report(code, "SPAM", null, uniqueIp()).status()).isEqualTo(201);
    }

    // ===========================
    //  ADMIN XEM XÉT
    // ===========================

    @Test
    void onlyAdminsCanReadOrCloseReports() throws Exception {
        String code = newLink();
        for (String[] call : new String[][]{
                {"GET", "/api/v1/admin/reports"},
                {"GET", "/api/v1/admin/reports/" + code},
                {"PUT", "/api/v1/admin/reports/" + code + "/resolve"},
                {"PUT", "/api/v1/admin/reports/" + code + "/dismiss"}}) {
            assertThat(api.call(call[0], call[1], null, null).status()).as(call[1]).isEqualTo(401);
            assertThat(api.call(call[0], call[1], owner, null).status()).as(call[1]).isEqualTo(403);
        }
    }

    @Test
    void adminSeesTheIndividualReportsOfALinkWithoutAnyReporterIdentity() throws Exception {
        String code = newLink();
        report(code, "ILLEGAL", "Sells counterfeit goods", uniqueIp());
        report(code, "OTHER", null, uniqueIp());

        Resp resp = api.call("GET", "/api/v1/admin/reports/" + code, admin, null);

        assertThat(resp.status()).isEqualTo(200);
        JsonNode reports = resp.body().get("data");
        assertThat(reports).hasSize(2);
        assertThat(reports.findValuesAsText("details")).contains("Sells counterfeit goods");
        assertThat(reports.toString()).doesNotContain("reporter").doesNotContain("hash");
        assertThat(api.call("GET", "/api/v1/admin/reports/nope1234", admin, null).status()).isEqualTo(404);
    }

    @Test
    void resolvingMovesTheReportsOutOfTheOpenList() throws Exception {
        String code = newLink();
        report(code, "SPAM", null, uniqueIp());
        report(code, "SPAM", null, uniqueIp());

        Resp resp = api.call("PUT", "/api/v1/admin/reports/" + code + "/resolve", admin, null);

        assertThat(resp.status()).isEqualTo(200);
        assertThat(resp.body().get("data").asInt()).isEqualTo(2);
        assertThat(openSummaries()).noneMatch(s -> s.get("shortCode").asText().equals(code));
        assertThat(summaries("RESOLVED")).anyMatch(s -> s.get("shortCode").asText().equals(code)
                && s.get("reportCount").asInt() == 2);
        // Gọi lại không còn báo cáo mở nào: thành công với 0
        assertThat(api.call("PUT", "/api/v1/admin/reports/" + code + "/resolve", admin, null)
                .body().get("data").asInt()).isZero();
    }

    @Test
    void dismissingKeepsTheLinkActiveAndRecordsTheReportsAsDismissed() throws Exception {
        String code = newLink();
        report(code, "SPAM", null, uniqueIp());

        assertThat(api.call("PUT", "/api/v1/admin/reports/" + code + "/dismiss", admin, null).status()).isEqualTo(200);

        assertThat(openSummaries()).noneMatch(s -> s.get("shortCode").asText().equals(code));
        assertThat(summaries("DISMISSED")).anyMatch(s -> s.get("shortCode").asText().equals(code));
        assertThat(urlRepository.findByShortCode(code).orElseThrow().getStatus().name()).isEqualTo("ACTIVE");
    }

    @Test
    void blockingTheLinkResolvesItsOpenReports() throws Exception {
        String code = newLink();
        report(code, "PHISHING", null, uniqueIp());
        assertThat(openSummary(code)).isNotNull();

        assertThat(api.call("PUT", "/api/v1/admin/urls/" + code + "/block", admin, "{\"reason\":\"phishing\"}")
                .status()).isEqualTo(200);

        assertThat(openSummaries()).noneMatch(s -> s.get("shortCode").asText().equals(code));
        assertThat(summaries("RESOLVED")).anyMatch(s -> s.get("shortCode").asText().equals(code));
    }

    @Test
    void anUnknownStatusFilterIsRejected() throws Exception {
        assertThat(api.call("GET", "/api/v1/admin/reports?status=NOPE", admin, null).status()).isEqualTo(400);
    }

    @Test
    void statementCountDoesNotGrowWithTheNumberOfReportedLinks() throws Exception {
        for (int i = 0; i < 3; i++) {
            report(newLink(), "SPAM", null, uniqueIp());
        }
        long few = statementsToListReports();
        for (int i = 0; i < 25; i++) {
            report(newLink(), i % 2 == 0 ? "SPAM" : "MALWARE", null, uniqueIp());
        }
        long many = statementsToListReports();

        assertThat(many).as("statements to list reports after adding 25 reported links").isEqualTo(few);
    }

    // ---------- helpers ----------

    private long statementsToListReports() throws Exception {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        try {
            Resp resp = api.call("GET", "/api/v1/admin/reports?size=100", admin, null);
            assertThat(resp.status()).isEqualTo(200);
            return statistics.getPrepareStatementCount();
        } finally {
            statistics.setStatisticsEnabled(false);
        }
    }

    private String newLink() throws Exception {
        return api.createUrl(owner, "https://example.com/report/" + UUID.randomUUID());
    }

    private Resp report(String code, String reason, String details, String ip) throws Exception {
        String body = "{\"shortCode\":\"" + code + "\",\"reason\":\"" + reason + "\""
                + (details == null ? "" : ",\"details\":\"" + details + "\"") + "}";
        return rawReport(body, ip);
    }

    private Resp rawReport(String body, String ip) throws Exception {
        return api.call("POST", "/api/v1/public/reports", null, body, Map.of("X-Forwarded-For", ip));
    }

    private List<JsonNode> summaries(String status) throws Exception {
        Resp resp = api.call("GET", "/api/v1/admin/reports?status=" + status + "&size=100", admin, null);
        assertThat(resp.status()).isEqualTo(200);
        List<JsonNode> all = new java.util.ArrayList<>();
        resp.body().get("data").get("content").forEach(all::add);
        return all;
    }

    private List<JsonNode> openSummaries() throws Exception {
        return summaries("OPEN");
    }

    private JsonNode openSummary(String code) throws Exception {
        return openSummaries().stream()
                .filter(s -> s.get("shortCode").asText().equals(code))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no open report summary for " + code));
    }

    /** IP riêng tư ngẫu nhiên: Tomcat tin X-Forwarded-For vì kết nối test đến từ loopback. */
    private static String uniqueIp() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        return "10." + r.nextInt(1, 255) + "." + r.nextInt(1, 255) + "." + r.nextInt(1, 255);
    }
}
