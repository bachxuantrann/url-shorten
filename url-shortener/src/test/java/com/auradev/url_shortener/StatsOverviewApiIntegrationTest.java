package com.auradev.url_shortener;

import com.auradev.url_shortener.entity.Url;
import com.auradev.url_shortener.event.ClickEvent;
import com.auradev.url_shortener.repository.UrlRepository;
import com.auradev.url_shortener.service.ClickIngestService;
import com.auradev.url_shortener.support.ApiClient;
import com.auradev.url_shortener.support.ApiClient.Resp;
import com.auradev.url_shortener.support.TestcontainersConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code GET /api/v1/stats/overview}: tổng quan của mọi link của người dùng (số link theo trạng thái, click theo
 * ngày, link nhiều click nhất, phân rã), qua security, validation và Postgres thật.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StatsOverviewApiIntegrationTest {

    private static final String CHROME_WINDOWS =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";
    private static final String SAFARI_IPHONE =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1";
    private static final String GOOGLEBOT = "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)";

    private static final String PATH = "/api/v1/stats/overview";

    @LocalServerPort
    private int port;

    @Autowired
    private ClickIngestService ingestService;

    @Autowired
    private UrlRepository urlRepository;

    @Autowired
    private MeterRegistry meters;

    private ApiClient api;
    private String owner;

    @BeforeEach
    void setUp() throws Exception {
        api = new ApiClient(port);
        owner = api.registerAndLogin();
    }

    // ===========================
    //  NỘI DUNG
    // ===========================

    @Test
    void aggregatesEveryLinkOfTheUserAndNothingElse() throws Exception {
        String a = newLink(owner);
        String b = newLink(owner);
        String deleted = newLink(owner);
        String stranger = newLink(api.registerAndLogin());
        LocalDateTime now = LocalDateTime.now();
        ingestService.ingest(List.of(
                click(a, now, "v1", "VN", CHROME_WINDOWS),
                click(a, now, "v1", "VN", CHROME_WINDOWS),
                click(a, now.minusDays(1), "v2", "US", SAFARI_IPHONE),
                click(b, now, "v3", "VN", CHROME_WINDOWS),
                click(b, now, "bot", "US", GOOGLEBOT),
                click(deleted, now, "v4", "FR", CHROME_WINDOWS),
                click(stranger, now, "v5", "JP", CHROME_WINDOWS)));
        assertThat(api.call("DELETE", "/api/v1/urls/" + deleted, owner, null).status()).isEqualTo(200);

        JsonNode data = overview(owner, "");

        // 5 click của a và b (kể cả 1 bot); click của link đã xoá và của người khác không tính
        assertThat(data.get("totals").get("clicks").asLong()).isEqualTo(5);
        assertThat(data.get("totals").get("botClicks").asLong()).isEqualTo(1);
        assertThat(data.get("totals").get("uniqueVisitors").asLong()).isEqualTo(3); // v1+v3 hôm nay, v2 hôm qua
        JsonNode daily = data.get("daily");
        assertThat(daily).hasSize(30);
        assertThat(daily.get(29).get("date").asText()).isEqualTo(LocalDate.now().toString());
        assertThat(daily.get(29).get("clicks").asLong()).isEqualTo(4);
        assertThat(daily.get(28).get("clicks").asLong()).isEqualTo(1);
        assertThat(daily.get(0).get("clicks").asLong()).isZero();

        assertThat(names(data.get("countries"))).containsExactly("VN", "US"); // FR (đã xoá) và JP (người khác) không có
        assertThat(clicks(data.get("countries"), "VN")).isEqualTo(3);
        assertThat(clicks(data.get("devices"), "BOT")).isEqualTo(1);
        assertThat(names(data.get("browsers"))).containsExactly("Chrome", "Safari");
    }

    @Test
    void rankedLinksAreSortedByClicksLimitedToFiveAndSkipLinksWithoutClicks() throws Exception {
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            codes.add(newLink(owner));
        }
        LocalDateTime now = LocalDateTime.now();
        List<ClickEvent> events = new ArrayList<>();
        // link i nhận i+1 click; link 6 nhiều nhất (7), link 0 ít nhất (1); thêm một link không click
        for (int i = 0; i < 7; i++) {
            for (int c = 0; c <= i; c++) {
                events.add(click(codes.get(i), now, "v" + i + "-" + c, "VN", CHROME_WINDOWS));
            }
        }
        newLink(owner);
        ingestService.ingest(events);

        JsonNode top = overview(owner, "").get("topLinks");

        assertThat(top).hasSize(5);
        assertThat(top.get(0).get("shortCode").asText()).isEqualTo(codes.get(6));
        assertThat(top.get(0).get("clicks").asLong()).isEqualTo(7);
        assertThat(top.get(4).get("shortCode").asText()).isEqualTo(codes.get(2));
        assertThat(top.get(0).get("shortUrl").asText()).endsWith("/" + codes.get(6));
        assertThat(top.get(0).get("originalUrl").asText()).startsWith("https://example.com/overview-");
    }

    @Test
    void countsLinksByStatusIncludingAnActiveLinkPastItsExpiry() throws Exception {
        String active = newLink(owner);
        String disabled = newLink(owner);
        String blocked = newLink(owner);
        String late = newLink(owner);
        String deleted = newLink(owner);
        assertThat(api.call("PUT", "/api/v1/urls/" + disabled + "/disable", owner, null).status()).isEqualTo(200);
        assertThat(api.call("PUT", "/api/v1/admin/urls/" + blocked + "/block", api.loginAsAdmin(),
                "{\"reason\":\"spam\"}").status()).isEqualTo(200);
        assertThat(api.call("DELETE", "/api/v1/urls/" + deleted, owner, null).status()).isEqualTo(200);
        // Quá hạn nhưng job chưa chạy: status trong DB vẫn là ACTIVE
        Url row = urlRepository.findByShortCode(late).orElseThrow();
        row.setExpiresAt(LocalDateTime.now().minusMinutes(5));
        urlRepository.save(row);

        JsonNode links = overview(owner, "").get("links");

        assertThat(links.get("total").asLong()).isEqualTo(4);
        assertThat(links.get("active").asLong()).isEqualTo(1);
        assertThat(links.get("disabled").asLong()).isEqualTo(1);
        assertThat(links.get("blocked").asLong()).isEqualTo(1);
        assertThat(links.get("expired").asLong()).isEqualTo(1); // link ACTIVE đã quá hạn
        assertThat(active).isNotBlank();
    }

    @Test
    void anAccountWithoutLinksGetsZerosAndEmptyLists() throws Exception {
        JsonNode data = overview(owner, "");

        assertThat(data.get("links").get("total").asLong()).isZero();
        assertThat(data.get("totals").get("clicks").asLong()).isZero();
        assertThat(data.get("daily")).hasSize(30);
        assertThat(data.get("topLinks")).isEmpty();
        assertThat(data.get("countries")).isEmpty();
        assertThat(data.get("devices")).isEmpty();
    }

    // ===========================
    //  KHOẢNG NGÀY
    // ===========================

    @Test
    void theRangeParametersAreInclusiveAndFilterClicksButNotLinkCounts() throws Exception {
        String code = newLink(owner);
        LocalDateTime now = LocalDateTime.now();
        ingestService.ingest(List.of(
                click(code, now, "v1", "VN", CHROME_WINDOWS),
                click(code, now.minusDays(5), "v1", "VN", CHROME_WINDOWS),
                click(code, now.minusDays(10), "v1", "VN", CHROME_WINDOWS)));
        String from = now.minusDays(5).toLocalDate().toString();
        String to = now.toLocalDate().toString();

        JsonNode data = overview(owner, "?from=" + from + "&to=" + to);

        assertThat(data.get("from").asText()).isEqualTo(from);
        assertThat(data.get("to").asText()).isEqualTo(to);
        assertThat(data.get("daily")).hasSize(6);
        assertThat(data.get("totals").get("clicks").asLong()).isEqualTo(2); // click cách đây 10 ngày nằm ngoài
        assertThat(data.get("topLinks").get(0).get("clicks").asLong()).isEqualTo(2);
        assertThat(data.get("links").get("total").asLong()).isEqualTo(1); // số link không phụ thuộc khoảng ngày
    }

    @Test
    void invalidRangesAreRejectedWith400AndTheMaximumRangeIsAccepted() throws Exception {
        Resp reversed = api.call("GET", PATH + "?from=2026-02-10&to=2026-02-01", owner, null);
        assertThat(reversed.status()).isEqualTo(400);
        assertThat(reversed.code()).isEqualTo("ERR_411");
        assertThat(api.call("GET", PATH + "?from=2025-01-01&to=2026-06-01", owner, null).code()).isEqualTo("ERR_411");
        assertThat(api.call("GET", PATH + "?from=yesterday", owner, null).status()).isEqualTo(400);

        LocalDate to = LocalDate.now();
        assertThat(overview(owner, "?from=" + to.minusDays(365) + "&to=" + to).get("daily")).hasSize(366);
    }

    // ===========================
    //  QUYỀN TRUY CẬP VÀ HIỆU NĂNG
    // ===========================

    @Test
    void requiresAuthentication() throws Exception {
        assertThat(api.call("GET", PATH, null, null).status()).isEqualTo(401);
    }

    @Test
    void aLinkWithTheAliasStatsIsStillReachableAndTheOverviewPathIsNotShadowed() throws Exception {
        // Đường dẫn tổng quan nằm ở /api/v1/stats nên không đụng alias "stats" hay "overview" của người dùng
        Resp alias = api.call("POST", "/api/v1/urls", owner,
                "{\"originalUrl\":\"https://example.com/alias-test\",\"alias\":\"stats\"}");
        assertThat(alias.status()).isEqualTo(201);

        assertThat(api.call("GET", "/api/v1/urls/stats", owner, null).status()).isEqualTo(200);
        assertThat(api.call("GET", PATH, owner, null).body().get("data").get("links").get("total").asLong()).isEqualTo(1);
    }

    @Test
    void theNumberOfSqlStatementsDoesNotGrowWithTheNumberOfLinks() throws Exception {
        List<ClickEvent> events = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            events.add(click(newLink(owner), LocalDateTime.now(), "v" + i, "VN", CHROME_WINDOWS));
        }
        ingestService.ingest(events);
        overview(owner, ""); // làm nóng (đăng nhập, bean, JIT) để chỉ đếm lần gọi đo
        int few = statementsForOverview();

        events.clear();
        for (int i = 0; i < 25; i++) {
            events.add(click(newLink(owner), LocalDateTime.now(), "w" + i, i % 2 == 0 ? "VN" : "US", CHROME_WINDOWS));
        }
        ingestService.ingest(events);
        int many = statementsForOverview();

        // Bộ đếm phải thật sự đếm được: tổng quan chạy bốn câu truy vấn (số link, theo ngày, top link, phân rã)
        assertThat(few).as("the counter must see the overview queries").isGreaterThanOrEqualTo(4);
        assertThat(many).as("SQL statements with 28 links vs 3").isEqualTo(few);
        assertThat(many).as("overview must stay a handful of statements").isLessThanOrEqualTo(8);
    }

    // ---------- helpers ----------

    /**
     * Số câu SQL chạy trong một lần gọi API tổng quan, đếm bằng timer {@code jdbc.query} của observation JDBC
     * (datasource-micrometer); mỗi câu lệnh gửi lên Postgres là một lần ghi nhận. Gồm cả câu của các thành phần khác
     * trong request nếu có.
     */
    private int statementsForOverview() throws Exception {
        long before = jdbcQueryCount();
        assertThat(api.call("GET", PATH, owner, null).status()).isEqualTo(200);
        return (int) (jdbcQueryCount() - before);
    }

    private long jdbcQueryCount() {
        return meters.find("jdbc.query").timers().stream().mapToLong(Timer::count).sum();
    }

    private JsonNode overview(String token, String query) throws Exception {
        Resp resp = api.call("GET", PATH + query, token, null);
        assertThat(resp.status()).isEqualTo(200);
        return resp.body().get("data");
    }

    private String newLink(String token) throws Exception {
        return api.createUrl(token, "https://example.com/overview-" + UUID.randomUUID());
    }

    private ClickEvent click(String code, LocalDateTime at, String visitor, String country, String userAgent) {
        return new ClickEvent(UUID.randomUUID().toString(), code, at, visitor, country, userAgent);
    }

    private List<String> names(JsonNode items) {
        List<String> names = new ArrayList<>();
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
}
