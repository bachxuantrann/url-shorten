package com.auradev.url_shortener;

import com.auradev.url_shortener.entity.Url;
import com.auradev.url_shortener.enums.UrlStatusEnum;
import com.auradev.url_shortener.repository.UrlRepository;
import com.auradev.url_shortener.support.TestcontainersConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test end-to-end qua HTTP thật (security filter, JWT, validation, exception handler).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UrlApiIntegrationTest {

    private static final String PASSWORD = "Passw0rdTest1";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @Autowired
    private UrlRepository urlRepository;

    private String alice;
    private String bob;

    @BeforeEach
    void registerUsers() throws Exception {
        alice = registerAndLogin();
        bob = registerAndLogin();
    }

    // ===========================
    //  CREATE
    // ===========================

    @Test
    void createReturns201WithShortCodeAndShortUrl() throws Exception {
        Resp resp = createUrl(alice, "https://example.com/some/long/path", null);

        assertThat(resp.status).isEqualTo(201);
        JsonNode data = resp.body.get("data");
        assertThat(data.get("shortCode").asText()).matches("[0-9a-zA-Z]{7}");
        assertThat(data.get("shortUrl").asText()).isEqualTo("http://localhost:8080/" + data.get("shortCode").asText());
        assertThat(data.get("originalUrl").asText()).isEqualTo("https://example.com/some/long/path");
        assertThat(data.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(data.get("clickCount").asLong()).isZero();
    }

    @Test
    void createRequiresAuthentication() throws Exception {
        Resp resp = call("POST", "/api/v1/urls", null, "{\"originalUrl\":\"https://example.com\"}");

        assertThat(resp.status).isEqualTo(401);
    }

    @Test
    void createRejectsInvalidUrls() throws Exception {
        for (String bad : new String[]{"ftp://example.com/f", "not a url", "javascript:alert(1)", "http://a@b.com"}) {
            Resp resp = createUrl(alice, bad, null);
            assertThat(resp.status).as(bad).isEqualTo(400);
            assertThat(resp.code()).as(bad).isEqualTo("ERR_404");
        }
    }

    @Test
    void createRejectsBlankUrlAndPastExpiry() throws Exception {
        Resp blank = call("POST", "/api/v1/urls", alice, "{\"originalUrl\":\"\"}");
        assertThat(blank.status).isEqualTo(400);
        assertThat(blank.code()).isEqualTo("ERR_001");

        Resp past = createUrl(alice, "https://example.com", LocalDateTime.now().minusDays(1));
        assertThat(past.status).isEqualTo(400);
        assertThat(past.code()).isEqualTo("ERR_001");
    }

    @Test
    void createAcceptsFutureExpiry() throws Exception {
        LocalDateTime expiry = LocalDateTime.now().plusDays(7).withNano(0);

        Resp resp = createUrl(alice, "https://example.com", expiry);

        assertThat(resp.status).isEqualTo(201);
        assertThat(resp.body.get("data").get("expiresAt").asText()).startsWith(expiry.toString().substring(0, 16));
    }

    @Test
    void eachCreatedLinkGetsADistinctCode() throws Exception {
        String first  = createCode(alice, "https://example.com/1");
        String second = createCode(alice, "https://example.com/1");

        assertThat(first).isNotEqualTo(second);
    }

    // ===========================
    //  READ + OWNERSHIP
    // ===========================

    @Test
    void getReturnsOwnLinkButHidesOthers() throws Exception {
        String code = createCode(alice, "https://example.com/mine");

        Resp own = call("GET", "/api/v1/urls/" + code, alice, null);
        assertThat(own.status).isEqualTo(200);
        assertThat(own.body.get("data").get("originalUrl").asText()).isEqualTo("https://example.com/mine");

        Resp other = call("GET", "/api/v1/urls/" + code, bob, null);
        assertThat(other.status).isEqualTo(404);
        assertThat(other.code()).isEqualTo("ERR_400");

        assertThat(call("GET", "/api/v1/urls/doesnotexist", alice, null).status).isEqualTo(404);
    }

    @Test
    void listReturnsOnlyOwnLinksWithPagination() throws Exception {
        for (int i = 0; i < 3; i++) {
            createCode(alice, "https://example.com/list/" + i);
        }
        createCode(bob, "https://example.com/bobs");

        Resp page = call("GET", "/api/v1/urls?size=2&page=0&sortBy=createdAt&sortDir=asc", alice, null);

        assertThat(page.status).isEqualTo(200);
        JsonNode data = page.body.get("data");
        assertThat(data.get("totalElements").asLong()).isEqualTo(3);
        assertThat(data.get("content")).hasSize(2);
        assertThat(data.get("totalPages").asInt()).isEqualTo(2);
        assertThat(data.get("content").get(0).get("originalUrl").asText()).endsWith("/list/0");

        JsonNode bobData = call("GET", "/api/v1/urls", bob, null).body.get("data");
        assertThat(bobData.get("totalElements").asLong()).isEqualTo(1);
    }

    @Test
    void listFiltersByStatusAndRejectsUnknownSortField() throws Exception {
        String active   = createCode(alice, "https://example.com/a");
        String disabled = createCode(alice, "https://example.com/b");
        call("PUT", "/api/v1/urls/" + disabled + "/disable", alice, null);

        JsonNode onlyDisabled = call("GET", "/api/v1/urls?status=DISABLED", alice, null).body.get("data");
        assertThat(onlyDisabled.get("totalElements").asLong()).isEqualTo(1);
        assertThat(onlyDisabled.get("content").get(0).get("shortCode").asText()).isEqualTo(disabled);
        assertThat(active).isNotEqualTo(disabled);

        assertThat(call("GET", "/api/v1/urls?sortBy=password", alice, null).status).isEqualTo(400);
        assertThat(call("GET", "/api/v1/urls?status=NOPE", alice, null).status).isEqualTo(400);
    }

    // ===========================
    //  UPDATE
    // ===========================

    @Test
    void updateChangesTargetAndExpiryAndClearsExpiry() throws Exception {
        String code = createCode(alice, "https://example.com/old");

        LocalDateTime expiry = LocalDateTime.now().plusDays(3).withNano(0);
        Resp updated = call("PUT", "/api/v1/urls/" + code, alice,
                "{\"originalUrl\":\"https://example.com/new\",\"expiresAt\":\"" + expiry + "\"}");
        assertThat(updated.status).isEqualTo(200);
        assertThat(updated.body.get("data").get("originalUrl").asText()).isEqualTo("https://example.com/new");
        assertThat(updated.body.get("data").get("expiresAt").isNull()).isFalse();

        Resp cleared = call("PUT", "/api/v1/urls/" + code, alice, "{\"originalUrl\":\"https://example.com/new\"}");
        assertThat(cleared.status).isEqualTo(200);
        assertThat(cleared.body.get("data").has("expiresAt") && !cleared.body.get("data").get("expiresAt").isNull())
                .isFalse();
    }

    @Test
    void updateCannotTouchOthersLinks() throws Exception {
        String code = createCode(alice, "https://example.com/alice");

        Resp resp = call("PUT", "/api/v1/urls/" + code, bob, "{\"originalUrl\":\"https://evil.example\"}");

        assertThat(resp.status).isEqualTo(404);
        assertThat(urlRepository.findAll().stream().filter(u -> u.getShortCode().equals(code)).findFirst().orElseThrow()
                .getOriginalUrl()).isEqualTo("https://example.com/alice");
    }

    @Test
    void updateRevivesExpiredLink() throws Exception {
        String code = createCode(alice, "https://example.com/expired");
        setStatus(code, UrlStatusEnum.EXPIRED);

        Resp resp = call("PUT", "/api/v1/urls/" + code, alice, "{\"originalUrl\":\"https://example.com/expired\"}");

        assertThat(resp.status).isEqualTo(200);
        assertThat(resp.body.get("data").get("status").asText()).isEqualTo("ACTIVE");
    }

    // ===========================
    //  DISABLE / ENABLE
    // ===========================

    @Test
    void disableAndEnableToggleStatusIdempotently() throws Exception {
        String code = createCode(alice, "https://example.com/toggle");

        assertThat(status(call("PUT", "/api/v1/urls/" + code + "/disable", alice, null))).isEqualTo("DISABLED");
        assertThat(status(call("PUT", "/api/v1/urls/" + code + "/disable", alice, null))).isEqualTo("DISABLED");
        assertThat(status(call("PUT", "/api/v1/urls/" + code + "/enable", alice, null))).isEqualTo("ACTIVE");
        assertThat(status(call("PUT", "/api/v1/urls/" + code + "/enable", alice, null))).isEqualTo("ACTIVE");
    }

    @Test
    void enableRejectsLinkWhoseExpiryHasPassed() throws Exception {
        String code = createCode(alice, "https://example.com/late");
        call("PUT", "/api/v1/urls/" + code + "/disable", alice, null);
        Url url = urlRepository.findAll().stream().filter(u -> u.getShortCode().equals(code)).findFirst().orElseThrow();
        url.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        urlRepository.save(url);

        Resp resp = call("PUT", "/api/v1/urls/" + code + "/enable", alice, null);

        assertThat(resp.status).isEqualTo(400);
        assertThat(resp.code()).isEqualTo("ERR_403");
    }

    @Test
    void expiredLinkCannotBeDisabledOrEnabledOnlyUpdated() throws Exception {
        String code = createCode(alice, "https://example.com/exp");
        setStatus(code, UrlStatusEnum.EXPIRED);

        Resp disable = call("PUT", "/api/v1/urls/" + code + "/disable", alice, null);
        Resp enable  = call("PUT", "/api/v1/urls/" + code + "/enable", alice, null);

        assertThat(disable.status).isEqualTo(409);
        assertThat(disable.code()).isEqualTo("ERR_402");
        assertThat(enable.status).isEqualTo(409);
    }

    // ===========================
    //  BLOCKED
    // ===========================

    @Test
    void blockedLinkCannotBeModifiedByOwner() throws Exception {
        String code = createCode(alice, "https://example.com/blocked");
        setStatus(code, UrlStatusEnum.BLOCKED);

        Resp[] responses = {
                call("PUT", "/api/v1/urls/" + code, alice, "{\"originalUrl\":\"https://example.com/x\"}"),
                call("PUT", "/api/v1/urls/" + code + "/disable", alice, null),
                call("PUT", "/api/v1/urls/" + code + "/enable", alice, null),
                call("DELETE", "/api/v1/urls/" + code, alice, null)
        };

        for (Resp resp : responses) {
            assertThat(resp.status).isEqualTo(403);
            assertThat(resp.code()).isEqualTo("ERR_401");
        }
        // Chủ link vẫn xem được link bị chặn
        assertThat(call("GET", "/api/v1/urls/" + code, alice, null).status).isEqualTo(200);
    }

    // ===========================
    //  DELETE
    // ===========================

    @Test
    void deleteIsSoftAndHidesLinkEverywhere() throws Exception {
        String code = createCode(alice, "https://example.com/delete-me");

        Resp deleted = call("DELETE", "/api/v1/urls/" + code, alice, null);
        assertThat(deleted.status).isEqualTo(200);

        assertThat(call("GET", "/api/v1/urls/" + code, alice, null).status).isEqualTo(404);
        assertThat(call("DELETE", "/api/v1/urls/" + code, alice, null).status).isEqualTo(404);
        assertThat(call("GET", "/api/v1/urls", alice, null).body.get("data").get("totalElements").asLong()).isZero();
        assertThat(call("GET", "/api/v1/urls?status=DELETED", alice, null).body.get("data").get("totalElements").asLong())
                .isZero();

        // Bản ghi vẫn còn trong DB (xoá mềm) và mã không được tái sử dụng
        Url row = urlRepository.findAll().stream().filter(u -> u.getShortCode().equals(code)).findFirst().orElseThrow();
        assertThat(row.getStatus()).isEqualTo(UrlStatusEnum.DELETED);
        assertThat(row.getDeletedAt()).isNotNull();
    }

    @Test
    void auditColumnsRecordTheOwnersUsername() throws Exception {
        String code = createCode(alice, "https://example.com/audit");

        Url row = urlRepository.findAll().stream().filter(u -> u.getShortCode().equals(code)).findFirst().orElseThrow();

        assertThat(row.getCreatedBy()).startsWith("user_");
        assertThat(row.getUpdatedBy()).isEqualTo(row.getCreatedBy());
        assertThat(row.getUserId()).isNotNull();
    }

    // ===========================
    //  SEARCH
    // ===========================

    @Test
    void searchMatchesUrlOrShortCodeCaseInsensitivelyAndOnlyOwnLinks() throws Exception {
        String shop = createCode(alice, "https://example.com/Shopping/Cart");
        String news = createCode(alice, "https://news.example.org/today");
        createCode(bob, "https://example.com/shopping/bobs");

        assertThat(searchCodes(alice, "shopping")).containsExactly(shop);
        assertThat(searchCodes(alice, "SHOPPING")).containsExactly(shop);
        assertThat(searchCodes(alice, "news.example")).containsExactly(news);
        assertThat(searchCodes(alice, "example")).containsExactlyInAnyOrder(shop, news);
        assertThat(searchCodes(alice, "  shopping  ")).containsExactly(shop);
        assertThat(searchCodes(alice, "no-such-thing")).isEmpty();

        // Tìm theo một phần short code, không phân biệt hoa thường
        assertThat(searchCodes(alice, shop.substring(1, 5).toUpperCase())).contains(shop);
    }

    @Test
    void searchTreatsWildcardCharactersLiterally() throws Exception {
        String percent    = createCode(alice, "https://example.com/100%25off");
        String underscore = createCode(alice, "https://example.com/a_b");
        String other      = createCode(alice, "https://example.com/axb");

        assertThat(searchCodes(alice, "%")).containsExactly(percent);
        assertThat(searchCodes(alice, "a_b")).containsExactly(underscore);
        assertThat(searchCodes(alice, "!")).isEmpty();
        assertThat(other).isNotIn(searchCodes(alice, "a_b"));
    }

    @Test
    void searchCombinesWithStatusFilterAndPagination() throws Exception {
        String first  = createCode(alice, "https://example.com/blog/1");
        String second = createCode(alice, "https://example.com/blog/2");
        createCode(alice, "https://example.com/blog/3");
        createCode(alice, "https://example.com/other");
        call("PUT", "/api/v1/urls/" + second + "/disable", alice, null);

        Resp disabled = search(alice, "keyword=blog&status=DISABLED");
        assertThat(disabled.body.get("data").get("totalElements").asLong()).isEqualTo(1);
        assertThat(disabled.body.get("data").get("content").get(0).get("shortCode").asText()).isEqualTo(second);

        Resp paged = search(alice, "keyword=blog&size=2&page=1&sortBy=createdAt&sortDir=asc");
        assertThat(paged.body.get("data").get("totalElements").asLong()).isEqualTo(3);
        assertThat(paged.body.get("data").get("content")).hasSize(1);
        assertThat(first).isNotNull();
    }

    @Test
    void blankKeywordReturnsEverythingAndOverlongKeywordIsRejected() throws Exception {
        createCode(alice, "https://example.com/one");
        createCode(alice, "https://example.com/two");

        assertThat(searchCodes(alice, "")).hasSize(2);
        assertThat(searchCodes(alice, "   ")).hasSize(2);

        Resp tooLong = search(alice, "keyword=" + "a".repeat(101));
        assertThat(tooLong.status).isEqualTo(400);
        assertThat(tooLong.code()).isEqualTo("ERR_001");
    }

    // ===========================
    //  HELPERS
    // ===========================

    private Resp search(String token, String query) throws Exception {
        return call("GET", "/api/v1/urls?" + query, token, null);
    }

    private List<String> searchCodes(String token, String keyword) throws Exception {
        Resp resp = search(token, "keyword=" + URLEncoder.encode(keyword, StandardCharsets.UTF_8));
        assertThat(resp.status).isEqualTo(200);
        List<String> codes = new ArrayList<>();
        resp.body.get("data").get("content").forEach(n -> codes.add(n.get("shortCode").asText()));
        return codes;
    }

    private record Resp(int status, JsonNode body) {
        String code() {
            return body.get("code").asText();
        }
    }

    private String status(Resp resp) {
        assertThat(resp.status).isEqualTo(200);
        return resp.body.get("data").get("status").asText();
    }

    private void setStatus(String code, UrlStatusEnum status) {
        Url url = urlRepository.findAll().stream().filter(u -> u.getShortCode().equals(code)).findFirst().orElseThrow();
        url.setStatus(status);
        urlRepository.save(url);
    }

    private Resp createUrl(String token, String originalUrl, LocalDateTime expiresAt) throws Exception {
        String body = expiresAt == null
                ? "{\"originalUrl\":\"" + originalUrl + "\"}"
                : "{\"originalUrl\":\"" + originalUrl + "\",\"expiresAt\":\"" + expiresAt.withNano(0) + "\"}";
        return call("POST", "/api/v1/urls", token, body);
    }

    private String createCode(String token, String originalUrl) throws Exception {
        Resp resp = createUrl(token, originalUrl, null);
        assertThat(resp.status).isEqualTo(201);
        return resp.body.get("data").get("shortCode").asText();
    }

    /** Đăng ký user mới (username duy nhất) và trả về access token. */
    private String registerAndLogin() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String username = "user_" + suffix;

        Resp register = call("POST", "/api/v1/auth/register", null,
                "{\"username\":\"" + username + "\",\"email\":\"" + username + "@test.local\","
                        + "\"password\":\"" + PASSWORD + "\",\"fullName\":\"Test User\"}");
        assertThat(register.status).isEqualTo(201);

        Resp login = call("POST", "/api/v1/auth/login", null,
                "{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}");
        assertThat(login.status).isEqualTo(200);
        return login.body.get("data").get("accessToken").asText();
    }

    private Resp call(String method, String path, String token, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .header("Accept-Language", "en")
                .method(method, body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        HttpResponse<String> response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        JsonNode json = response.body().isBlank() ? JSON.createObjectNode() : JSON.readTree(response.body());
        return new Resp(response.statusCode(), json);
    }
}
