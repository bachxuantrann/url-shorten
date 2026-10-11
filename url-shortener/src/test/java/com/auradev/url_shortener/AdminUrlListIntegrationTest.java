package com.auradev.url_shortener;

import com.auradev.url_shortener.entity.Url;
import com.auradev.url_shortener.enums.UrlStatusEnum;
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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code GET /api/v1/admin/urls}: admin liệt kê và tìm link của mọi người (kể cả link ẩn danh).
 * Mỗi test dùng một "dấu" duy nhất trong URL đích để chỉ nhìn thấy dữ liệu của chính nó.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminUrlListIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private UrlRepository urlRepository;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private ApiClient api;
    private String admin;
    private String user;
    private String marker;

    @BeforeEach
    void setUp() throws Exception {
        api = new ApiClient(port);
        admin = api.loginAsAdmin();
        user = api.registerAndLogin();
        marker = "adm" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    @Test
    void onlyAdminsMayListLinks() throws Exception {
        assertThat(api.call("GET", "/api/v1/admin/urls", null, null).status()).isEqualTo(401);
        assertThat(api.call("GET", "/api/v1/admin/urls", user, null).status()).isEqualTo(403);
        assertThat(api.call("GET", "/api/v1/admin/urls", admin, null).status()).isEqualTo(200);
    }

    @Test
    void listsLinksOfEveryoneIncludingAnonymousButNotDeletedOnes() throws Exception {
        String owned = api.createUrl(user, "https://example.com/" + marker + "/owned");
        String anonymous = anonymousCreate("https://example.com/" + marker + "/anon");
        String deleted = api.createUrl(user, "https://example.com/" + marker + "/deleted");
        assertThat(api.call("DELETE", "/api/v1/urls/" + deleted, user, null).status()).isEqualTo(200);

        Resp resp = search(marker, null);

        assertThat(resp.status()).isEqualTo(200);
        JsonNode page = resp.body().get("data");
        assertThat(page.get("totalElements").asInt()).isEqualTo(2);
        assertThat(codes(page)).containsExactlyInAnyOrder(owned, anonymous);
        JsonNode anon = find(page, anonymous);
        // JSON bỏ trường null: link ẩn danh không có userId
        assertThat(anon.path("userId").isMissingNode() || anon.path("userId").isNull()).isTrue();
        assertThat(anon.get("createdBy").asText()).isEqualTo("anonymous");
        assertThat(find(page, owned).get("userId").asText()).isNotBlank();
    }

    @Test
    void keywordMatchesTargetCodeAndCreatorCaseInsensitively() throws Exception {
        String code = api.createUrl(user, "https://example.com/" + marker + "/Target");

        assertThat(codes(search(marker.toUpperCase(), null).body().get("data"))).containsExactly(code);
        assertThat(codes(search(code.toLowerCase(), null).body().get("data"))).contains(code);
        // "anonymous" là người tạo của link ẩn danh
        String anon = anonymousCreate("https://example.com/" + marker + "/anon2");
        assertThat(codes(search("ANONYMOUS", null).body().get("data"))).contains(anon);
        assertThat(codes(search(marker + "/zzz", null).body().get("data"))).isEmpty();
    }

    @Test
    void likeWildcardsInTheKeywordAreTreatedLiterally() throws Exception {
        api.createUrl(user, "https://example.com/" + marker + "/plain");

        // Không URL nào chứa dấu '%' thật: nếu nó bị coi là wildcard thì sẽ khớp mọi link
        assertThat(search("%", null).body().get("data").get("totalElements").asInt()).isZero();
        assertThat(codes(search(marker + "_plain", null).body().get("data"))).isEmpty();
        assertThat(codes(search(marker + "%", null).body().get("data"))).isEmpty();
    }

    @Test
    void filtersByStatusAndReportsAnExpiredActiveLinkAsExpired() throws Exception {
        String active = api.createUrl(user, "https://example.com/" + marker + "/active");
        String blocked = api.createUrl(user, "https://example.com/" + marker + "/blocked");
        assertThat(api.call("PUT", "/api/v1/admin/urls/" + blocked + "/block", admin, "{\"reason\":\"spam\"}")
                .status()).isEqualTo(200);
        // Quá hạn nhưng job chưa chạy: status trong DB vẫn là ACTIVE
        Url late = urlRepository.save(Url.builder()
                .userId(null).shortCode("L" + marker.substring(3, 12)).originalUrl("https://example.com/" + marker + "/late")
                .status(UrlStatusEnum.ACTIVE).expiresAt(LocalDateTime.now().minusMinutes(5)).build());

        JsonNode all = search(marker, null).body().get("data");
        assertThat(find(all, late.getShortCode()).get("status").asText()).isEqualTo("EXPIRED");
        assertThat(find(all, active).get("status").asText()).isEqualTo("ACTIVE");
        JsonNode onlyBlocked = search(marker, "BLOCKED").body().get("data");
        assertThat(codes(onlyBlocked)).containsExactly(blocked);
        assertThat(find(onlyBlocked, blocked).get("blockedReason").asText()).isEqualTo("spam");
        // Bộ lọc dùng status lưu trong DB, như API của người dùng
        assertThat(codes(search(marker, "DELETED").body().get("data"))).isEmpty();
    }

    @Test
    void sortsAndPagesDeterministically() throws Exception {
        List<String> created = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            created.add(api.createUrl(user, "https://example.com/" + marker + "/" + i));
        }

        Resp first = api.call("GET", path(marker, null) + "&size=2&page=0&sortBy=createdAt&sortDir=asc", admin, null);
        Resp last = api.call("GET", path(marker, null) + "&size=2&page=2&sortBy=createdAt&sortDir=asc", admin, null);

        assertThat(codes(first.body().get("data"))).containsExactly(created.get(0), created.get(1));
        assertThat(codes(last.body().get("data"))).containsExactly(created.get(4));
        assertThat(first.body().get("data").get("totalElements").asInt()).isEqualTo(5);
        assertThat(first.body().get("data").get("hasNext").asBoolean()).isTrue();
    }

    @Test
    void rejectsUnknownSortFieldsAndTooLongKeywords() throws Exception {
        assertThat(api.call("GET", "/api/v1/admin/urls?sortBy=passwordHash", admin, null).status()).isEqualTo(400);
        assertThat(api.call("GET", "/api/v1/admin/urls?keyword=" + "a".repeat(101), admin, null).status())
                .isEqualTo(400);
        assertThat(api.call("GET", "/api/v1/admin/urls?status=NOPE", admin, null).status()).isEqualTo(400);
    }

    @Test
    void statementCountDoesNotGrowWithTheNumberOfLinks() throws Exception {
        long few = statementsToList(3);
        long many = statementsToList(30);

        assertThat(many).as("statements for 30 links vs 3").isEqualTo(few);
    }

    // ---------- helpers ----------

    /** Tạo thêm link cho tới khi có {@code total} link mang dấu của test này rồi đếm số câu lệnh SQL của một lần list. */
    private long statementsToList(int total) throws Exception {
        int existing = search(marker, null).body().get("data").get("totalElements").asInt();
        for (int i = existing; i < total; i++) {
            api.createUrl(user, "https://example.com/" + marker + "/n" + i);
        }
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        try {
            Resp resp = api.call("GET", path(marker, null) + "&size=100", admin, null);
            assertThat(resp.body().get("data").get("content")).hasSize(total);
            return statistics.getPrepareStatementCount();
        } finally {
            statistics.setStatisticsEnabled(false);
        }
    }

    private String anonymousCreate(String target) throws Exception {
        String ip = "10." + (int) (Math.random() * 250) + "." + (int) (Math.random() * 250) + "." + (1 + (int) (Math.random() * 250));
        Resp resp = api.call("POST", "/api/v1/public/urls", null, "{\"originalUrl\":\"" + target + "\"}",
                Map.of("X-Forwarded-For", ip));
        assertThat(resp.status()).isEqualTo(201);
        return resp.body().get("data").get("shortCode").asText();
    }

    private Resp search(String keyword, String status) throws Exception {
        return api.call("GET", path(keyword, status), admin, null);
    }

    private static String path(String keyword, String status) {
        String q = "/api/v1/admin/urls?keyword=" + URLEncoder.encode(keyword, StandardCharsets.UTF_8);
        return status == null ? q : q + "&status=" + status;
    }

    private static List<String> codes(JsonNode page) {
        List<String> codes = new ArrayList<>();
        page.get("content").forEach(n -> codes.add(n.get("shortCode").asText()));
        return codes;
    }

    private static JsonNode find(JsonNode page, String code) {
        for (JsonNode n : page.get("content")) {
            if (n.get("shortCode").asText().equals(code)) {
                return n;
            }
        }
        throw new AssertionError("link " + code + " not in page");
    }
}
