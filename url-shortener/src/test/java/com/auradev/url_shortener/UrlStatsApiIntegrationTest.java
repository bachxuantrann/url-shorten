package com.auradev.url_shortener;

import com.auradev.url_shortener.event.ClickEvent;
import com.auradev.url_shortener.service.ClickIngestService;
import com.auradev.url_shortener.support.ApiClient;
import com.auradev.url_shortener.support.ApiClient.Resp;
import com.auradev.url_shortener.support.TestcontainersConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test API thống kê: {@code GET /api/v1/urls/{code}/stats} (chủ link) và
 * {@code GET /api/v1/admin/urls/{code}/stats} (admin), đi qua security, validation và Postgres thật.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UrlStatsApiIntegrationTest {

    private static final String CHROME_WINDOWS =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";
    private static final String SAFARI_IPHONE =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1";
    private static final String GOOGLEBOT = "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)";

    @LocalServerPort
    private int port;

    @Autowired
    private ClickIngestService ingestService;

    private ApiClient api;
    private String owner;
    private String code;

    @BeforeEach
    void setUp() throws Exception {
        api = new ApiClient(port);
        owner = api.registerAndLogin();
        code = api.createUrl(owner, "https://example.com/stats-" + shortId());
    }

    // ===========================
    //  NỘI DUNG THỐNG KÊ
    // ===========================

    @Test
    void ownerSeesTotalsDailySeriesAndBreakdowns() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        ingestService.ingest(List.of(
                click(now, "v1", "VN", CHROME_WINDOWS),
                click(now, "v1", "VN", CHROME_WINDOWS),
                click(now, "v2", "US", SAFARI_IPHONE),
                click(now.minusDays(1), "v1", null, CHROME_WINDOWS),
                click(now, "bot", "US", GOOGLEBOT)));

        Resp resp = api.call("GET", "/api/v1/urls/" + code + "/stats", owner, null);

        assertThat(resp.status()).isEqualTo(200);
        JsonNode data = resp.body().get("data");
        assertThat(data.get("shortCode").asText()).isEqualTo(code);
        assertThat(data.get("totals").get("clicks").asLong()).isEqualTo(5);
        assertThat(data.get("totals").get("botClicks").asLong()).isEqualTo(1);
        assertThat(data.get("totals").get("uniqueVisitors").asLong()).isEqualTo(3); // v1+v2 hôm nay, v1 hôm qua

        JsonNode daily = data.get("daily");
        assertThat(daily).hasSize(30);
        JsonNode last = daily.get(29);
        assertThat(last.get("date").asText()).isEqualTo(LocalDate.now().toString());
        assertThat(last.get("clicks").asLong()).isEqualTo(4);
        assertThat(last.get("uniqueVisitors").asLong()).isEqualTo(2);
        assertThat(daily.get(28).get("clicks").asLong()).isEqualTo(1);
        assertThat(daily.get(0).get("clicks").asLong()).isZero(); // ngày không có click được điền 0

        // Quốc gia chỉ tính khách thật: bot US không được cộng; click không rõ quốc gia vào "Unknown"
        // US và Unknown cùng 1 click nên xếp theo tên
        assertThat(names(data.get("countries"))).containsExactly("VN", "US", "Unknown");
        assertThat(clicks(data.get("countries"), "VN")).isEqualTo(2);
        assertThat(clicks(data.get("countries"), "US")).isEqualTo(1);
        assertThat(clicks(data.get("devices"), "DESKTOP")).isEqualTo(3);
        assertThat(clicks(data.get("devices"), "MOBILE")).isEqualTo(1);
        assertThat(clicks(data.get("devices"), "BOT")).isEqualTo(1);
        assertThat(names(data.get("browsers"))).containsExactly("Chrome", "Safari");
        assertThat(names(data.get("operatingSystems"))).containsExactly("Windows", "iOS");
    }

    @Test
    void aLinkWithoutClicksReturnsZerosAndEmptyBreakdowns() throws Exception {
        Resp resp = api.call("GET", "/api/v1/urls/" + code + "/stats", owner, null);

        assertThat(resp.status()).isEqualTo(200);
        JsonNode data = resp.body().get("data");
        assertThat(data.get("totals").get("clicks").asLong()).isZero();
        assertThat(data.get("daily")).hasSize(30);
        assertThat(data.get("countries")).isEmpty();
        assertThat(data.get("devices")).isEmpty();
    }

    @Test
    void theRangeParametersAreInclusiveAndFilterTheData() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        ingestService.ingest(List.of(
                click(now, "v1", null, CHROME_WINDOWS),
                click(now.minusDays(5), "v1", null, CHROME_WINDOWS),
                click(now.minusDays(10), "v1", null, CHROME_WINDOWS)));
        String from = now.minusDays(5).toLocalDate().toString();
        String to = now.toLocalDate().toString();

        Resp resp = api.call("GET", "/api/v1/urls/" + code + "/stats?from=" + from + "&to=" + to, owner, null);

        assertThat(resp.status()).isEqualTo(200);
        JsonNode data = resp.body().get("data");
        assertThat(data.get("from").asText()).isEqualTo(from);
        assertThat(data.get("to").asText()).isEqualTo(to);
        assertThat(data.get("daily")).hasSize(6);
        assertThat(data.get("totals").get("clicks").asLong()).isEqualTo(2); // click cách đây 10 ngày nằm ngoài
        assertThat(clicks(data.get("browsers"), "Chrome")).isEqualTo(2);
    }

    // ===========================
    //  VALIDATION
    // ===========================

    @Test
    void invalidRangesAreRejectedWith400() throws Exception {
        String path = "/api/v1/urls/" + code + "/stats";

        Resp reversed = api.call("GET", path + "?from=2026-02-10&to=2026-02-01", owner, null);
        assertThat(reversed.status()).isEqualTo(400);
        assertThat(reversed.code()).isEqualTo("ERR_411");

        Resp tooLong = api.call("GET", path + "?from=2025-01-01&to=2026-06-01", owner, null);
        assertThat(tooLong.status()).isEqualTo(400);
        assertThat(tooLong.code()).isEqualTo("ERR_411");

        Resp notADate = api.call("GET", path + "?from=yesterday", owner, null);
        assertThat(notADate.status()).isEqualTo(400);
    }

    @Test
    void aRangeOfExactlyTheMaximumLengthIsAccepted() throws Exception {
        LocalDate to = LocalDate.now();
        LocalDate from = to.minusDays(365); // 366 ngày, cả hai đầu

        Resp resp = api.call("GET", "/api/v1/urls/" + code + "/stats?from=" + from + "&to=" + to, owner, null);

        assertThat(resp.status()).isEqualTo(200);
        assertThat(resp.body().get("data").get("daily")).hasSize(366);
    }

    // ===========================
    //  QUYỀN TRUY CẬP
    // ===========================

    @Test
    void anotherUserGets404AndNoTokenGets401() throws Exception {
        String stranger = api.registerAndLogin();

        assertThat(api.call("GET", "/api/v1/urls/" + code + "/stats", stranger, null).status()).isEqualTo(404);
        assertThat(api.call("GET", "/api/v1/urls/" + code + "/stats", null, null).status()).isEqualTo(401);
        assertThat(api.call("GET", "/api/v1/urls/nosuch" + shortId() + "/stats", owner, null).status()).isEqualTo(404);
    }

    @Test
    void ordinaryUsersCannotUseTheAdminEndpoint() throws Exception {
        assertThat(api.call("GET", "/api/v1/admin/urls/" + code + "/stats", owner, null).status()).isEqualTo(403);
        assertThat(api.call("GET", "/api/v1/admin/urls/" + code + "/stats", null, null).status()).isEqualTo(401);
    }

    @Test
    void anAdminSeesTheStatsOfAnyLinkIncludingAnonymousOnes() throws Exception {
        String admin = api.loginAsAdmin();
        ingestService.ingest(List.of(click(LocalDateTime.now(), "v1", "VN", CHROME_WINDOWS)));
        Resp created = api.call("POST", "/api/v1/public/urls", null, "{\"originalUrl\":\"https://example.com/anon-" + shortId() + "\"}",
                Map.of("X-Forwarded-For", "198.51.100." + (1 + (int) (Math.random() * 250))));
        String anonymous = created.body().get("data").get("shortCode").asText();

        Resp ownerLink = api.call("GET", "/api/v1/admin/urls/" + code + "/stats", admin, null);
        Resp anonymousLink = api.call("GET", "/api/v1/admin/urls/" + anonymous + "/stats", admin, null);

        assertThat(ownerLink.status()).isEqualTo(200);
        assertThat(ownerLink.body().get("data").get("totals").get("clicks").asLong()).isEqualTo(1);
        assertThat(anonymousLink.status()).isEqualTo(200);
        assertThat(anonymousLink.body().get("data").get("totals").get("clicks").asLong()).isZero();
    }

    @Test
    void deletedLinksHaveNoStatsForAnyone() throws Exception {
        api.call("DELETE", "/api/v1/urls/" + code, owner, null);

        assertThat(api.call("GET", "/api/v1/urls/" + code + "/stats", owner, null).status()).isEqualTo(404);
        assertThat(api.call("GET", "/api/v1/admin/urls/" + code + "/stats", api.loginAsAdmin(), null).status()).isEqualTo(404);
    }

    // ===========================
    //  HELPERS
    // ===========================

    private ClickEvent click(LocalDateTime at, String visitor, String country, String userAgent) {
        return new ClickEvent(UUID.randomUUID().toString(), code, at, visitor, country, userAgent);
    }

    private List<String> names(JsonNode items) {
        List<String> names = new java.util.ArrayList<>();
        items.forEach(item -> names.add(item.get("name").asText()));
        return names;
    }

    private long clicks(JsonNode items, String name) {
        for (JsonNode item : items) {
            if (item.get("name").asText().equals(name)) {
                return item.get("clicks").asLong();
            }
        }
        return -1;
    }

    private String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
}
