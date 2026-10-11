package com.auradev.url_shortener;

import com.auradev.url_shortener.entity.Url;
import com.auradev.url_shortener.repository.UrlRepository;
import com.auradev.url_shortener.support.ApiClient;
import com.auradev.url_shortener.support.ApiClient.BinaryResp;
import com.auradev.url_shortener.support.ApiClient.Resp;
import com.auradev.url_shortener.support.QrDecoder;
import com.auradev.url_shortener.support.TestcontainersConfiguration;
import com.auradev.url_shortener.utils.ShortCodeGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 5 end-to-end qua HTTP thật: alias tuỳ chỉnh, link có mật khẩu (kèm giới hạn thử) và mã QR.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LinkExtrasApiIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private UrlRepository urlRepository;

    @Autowired
    private ShortCodeGenerator shortCodeGenerator;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    private ApiClient api;
    private String owner;
    private String other;

    @BeforeEach
    void setUp() throws Exception {
        api = new ApiClient(port);
        owner = api.registerAndLogin();
        other = api.registerAndLogin();
    }

    // ===========================
    //  CUSTOM ALIAS
    // ===========================

    @Test
    void anAliasBecomesTheShortCodeAndRedirects() throws Exception {
        String alias = "my-" + shortId();

        Resp created = createWithAlias(owner, "https://example.com/aliased", alias);

        assertThat(created.status()).isEqualTo(201);
        assertThat(created.body().get("data").get("shortCode").asText()).isEqualTo(alias);
        assertThat(created.body().get("data").get("shortUrl").asText()).isEqualTo("http://localhost:8080/" + alias);
        Resp redirect = api.call("GET", "/" + alias, null, null);
        assertThat(redirect.status()).isEqualTo(302);
        assertThat(redirect.header("Location")).isEqualTo("https://example.com/aliased");
        assertThat(api.call("GET", "/api/v1/urls/" + alias, owner, null).status()).isEqualTo(200);
    }

    @Test
    void anAliasAlreadyTakenIsRejectedForEveryone() throws Exception {
        String alias = "taken-" + shortId();
        assertThat(createWithAlias(owner, "https://example.com/first", alias).status()).isEqualTo(201);

        Resp sameUser = createWithAlias(owner, "https://example.com/second", alias);
        Resp otherUser = createWithAlias(other, "https://example.com/third", alias);

        for (Resp resp : new Resp[]{sameUser, otherUser}) {
            assertThat(resp.status()).isEqualTo(409);
            assertThat(resp.code()).isEqualTo("ERR_407");
        }
        // Link đầu tiên không bị ảnh hưởng
        assertThat(api.call("GET", "/" + alias, null, null).header("Location")).isEqualTo("https://example.com/first");
    }

    @Test
    void reservedAliasesAreUnavailableAndMalformedOnesAreValidationErrors() throws Exception {
        for (String reserved : new String[]{"admin", "api", "actuator", "ADMIN"}) {
            Resp resp = createWithAlias(owner, "https://example.com/x", reserved);
            assertThat(resp.status()).as(reserved).isEqualTo(409);
            assertThat(resp.code()).as(reserved).isEqualTo("ERR_407");
        }
        for (String malformed : new String[]{"a b", "ab", "x".repeat(33), "has.dot", "with/slash", "ünï-code"}) {
            Resp resp = createWithAlias(owner, "https://example.com/x", malformed);
            assertThat(resp.status()).as(malformed).isEqualTo(400);
            assertThat(resp.code()).as(malformed).isEqualTo("ERR_001");
        }
    }

    @Test
    void aMaximumLengthAliasFitsTheDatabaseColumn() throws Exception {
        String alias = "l" + shortId() + "-".repeat(23); // đúng 32 ký tự, mức tối đa cho phép
        assertThat(alias).hasSize(32);

        Resp created = createWithAlias(owner, "https://example.com/long", alias);

        assertThat(created.status()).isEqualTo(201);
        assertThat(api.call("GET", "/" + alias, null, null).status()).isEqualTo(302);
    }

    @Test
    void aliasesAreCaseSensitive() throws Exception {
        String id = shortId();
        assertThat(createWithAlias(owner, "https://example.com/upper", "MyLink" + id).status()).isEqualTo(201);
        assertThat(createWithAlias(owner, "https://example.com/lower", "mylink" + id).status()).isEqualTo(201);

        assertThat(api.call("GET", "/MyLink" + id, null, null).header("Location")).isEqualTo("https://example.com/upper");
        assertThat(api.call("GET", "/mylink" + id, null, null).header("Location")).isEqualTo("https://example.com/lower");
    }

    @Test
    void aDeletedAliasCanNeverBeReused() throws Exception {
        String alias = "gone-" + shortId();
        assertThat(createWithAlias(owner, "https://example.com/a", alias).status()).isEqualTo(201);
        api.call("DELETE", "/api/v1/urls/" + alias, owner, null);

        Resp again = createWithAlias(other, "https://example.com/b", alias);

        assertThat(again.status()).isEqualTo(409);
        assertThat(again.code()).isEqualTo("ERR_407");
        assertThat(api.call("GET", "/" + alias, null, null).status()).isEqualTo(410);
    }

    /**
     * Kịch bản khó nhất: một alias 7 ký tự trùng đúng mã mà bộ sinh mã sắp cấp. INSERT vi phạm UNIQUE làm
     * Postgres huỷ transaction, nên việc tạo link phải thử lại ở một transaction mới với mã khác thay vì lỗi.
     * Chạy trên Postgres thật để chứng minh tên ràng buộc được nhận diện và transaction hỏng không bị dùng lại.
     */
    @Test
    void aGeneratedCodeThatCollidesWithAnAliasIsRetriedTransparently() throws Exception {
        long consumed = urlRepository.nextShortCodeSequence();
        String collidingCode = shortCodeGenerator.generate(consumed + 1); // mã sẽ được cấp cho link kế tiếp
        String expectedAfterRetry = shortCodeGenerator.generate(consumed + 2);
        assertThat(collidingCode).hasSize(7);
        assertThat(createWithAlias(owner, "https://example.com/squatter", collidingCode).status()).isEqualTo(201);

        Resp created = api.call("POST", "/api/v1/urls", owner, "{\"originalUrl\":\"https://example.com/victim\"}");

        assertThat(created.status()).isEqualTo(201);
        String code = created.body().get("data").get("shortCode").asText();
        assertThat(code).isEqualTo(expectedAfterRetry).isNotEqualTo(collidingCode);
        // Cả hai link đều nguyên vẹn và trỏ đúng đích của mình
        assertThat(api.call("GET", "/" + collidingCode, null, null).header("Location")).isEqualTo("https://example.com/squatter");
        assertThat(api.call("GET", "/" + code, null, null).header("Location")).isEqualTo("https://example.com/victim");
    }

    @Test
    void theAnonymousEndpointIgnoresAliasAndPassword() throws Exception {
        String alias = "sneaky-" + shortId();

        Resp resp = api.call("POST", "/api/v1/public/urls", null,
                "{\"originalUrl\":\"https://example.com/anon\",\"alias\":\"" + alias + "\",\"password\":\"secret\"}",
                Map.of("X-Forwarded-For", uniqueIp()));

        assertThat(resp.status()).isEqualTo(201);
        String code = resp.body().get("data").get("shortCode").asText();
        assertThat(code).isNotEqualTo(alias).hasSize(7);
        assertThat(resp.body().get("data").get("passwordProtected").asBoolean()).isFalse();
        assertThat(api.call("GET", "/" + code, null, null).status()).isEqualTo(302);
    }

    // ===========================
    //  PASSWORD: creation and redirect
    // ===========================

    @Test
    void aLinkCreatedWithAPasswordIsProtectedAndNeverLeaksIt() throws Exception {
        Resp created = createWithPassword(owner, "https://example.com/secret-page", "s3cret-pw");

        assertThat(created.status()).isEqualTo(201);
        assertThat(created.body().get("data").get("passwordProtected").asBoolean()).isTrue();
        String json = created.body().toString();
        assertThat(json).doesNotContain("s3cret-pw").doesNotContain("passwordHash").doesNotContain("$2a$").doesNotContain("$2b$");

        String code = created.body().get("data").get("shortCode").asText();
        Url row = urlRepository.findByShortCode(code).orElseThrow();
        assertThat(row.getPasswordHash()).startsWith("$2").isNotEqualTo("s3cret-pw");

        Resp redirect = api.call("GET", "/" + code, null, null);
        assertThat(redirect.status()).isEqualTo(403);
        assertThat(redirect.code()).isEqualTo("ERR_408");
        assertThat(redirect.header("Location")).isNull();
    }

    @Test
    void aBrowserOpeningAProtectedLinkIsSentToTheUnlockPageWhileApiClientsKeepTheJson403() throws Exception {
        String code = createWithPassword(owner, "https://example.com/browser-secret", "s3cret-pw")
                .body().get("data").get("shortCode").asText();

        Resp browser = api.call("GET", "/" + code, null, null,
                Map.of("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"));
        Resp apiClient = api.call("GET", "/" + code, null, null, Map.of("Accept", "application/json"));

        assertThat(browser.status()).isEqualTo(302);
        assertThat(browser.header("Location")).isEqualTo("https://ui.test/unlock/" + code);
        assertThat(browser.header("Cache-Control")).contains("no-store");
        assertThat(apiClient.status()).isEqualTo(403);
        assertThat(apiClient.code()).isEqualTo("ERR_408");
    }

    @Test
    void aDisabledProtectedLinkNeverSendsABrowserToTheUnlockPage() throws Exception {
        String code = createWithPassword(owner, "https://example.com/off", "s3cret-pw")
                .body().get("data").get("shortCode").asText();
        urlRepository.findByShortCode(code).ifPresent(u -> {
            u.setStatus(com.auradev.url_shortener.enums.UrlStatusEnum.DISABLED);
            urlRepository.save(u);
        });
        // Xoá cache để lần redirect đọc lại trạng thái mới từ DB
        redisTemplate.delete("URL:" + code);

        Resp browser = api.call("GET", "/" + code, null, null,
                Map.of("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"));

        assertThat(browser.status()).isEqualTo(302);
        // Link đã tắt báo như không tồn tại (không lộ rằng nó có mật khẩu): sang trang "không tìm thấy", không phải trang unlock
        assertThat(browser.header("Location")).isEqualTo("https://ui.test/unavailable/not-found");
    }

    @Test
    void uiRoutesAreReservedSoNoAliasCanShadowThem() throws Exception {
        for (String route : new String[]{"unlock", "dashboard", "links", "settings", "profile"}) {
            Resp resp = createWithAlias(owner, "https://example.com/x", route);
            assertThat(resp.status()).as(route).isEqualTo(409);
            assertThat(resp.code()).as(route).isEqualTo("ERR_407");
        }
    }

    @Test
    void theCacheOfAProtectedLinkHoldsNeitherTheTargetNorTheHash() throws Exception {
        String code = createWithPassword(owner, "https://example.com/very-secret-target", "s3cret-pw")
                .body().get("data").get("shortCode").asText();
        api.call("GET", "/" + code, null, null); // nạp cache

        String raw = redisTemplate.opsForValue().get("URL:" + code);

        assertThat(raw).isEqualTo("ACTIVE|-|P|");
    }

    // ===========================
    //  PASSWORD: unlock
    // ===========================

    @Test
    void unlockReturnsTheTargetForTheRightPasswordAndRejectsTheWrongOne() throws Exception {
        String code = createWithPassword(owner, "https://example.com/unlock-me", "right-pw")
                .body().get("data").get("shortCode").asText();
        String ip = uniqueIp();

        Resp wrong = unlock(code, "wrong-pw", ip);
        assertThat(wrong.status()).isEqualTo(403);
        assertThat(wrong.code()).isEqualTo("ERR_409");
        assertThat(wrong.body().toString()).doesNotContain("example.com/unlock-me");

        Resp right = unlock(code, "right-pw", ip);
        assertThat(right.status()).isEqualTo(200);
        assertThat(right.body().get("data").get("originalUrl").asText()).isEqualTo("https://example.com/unlock-me");
        assertThat(right.header("Cache-Control")).contains("no-store");
    }

    @Test
    void unlockOfALinkWithoutAPasswordJustReturnsTheTarget() throws Exception {
        String code = api.createUrl(owner, "https://example.com/plain");

        Resp resp = unlock(code, "whatever", uniqueIp());

        assertThat(resp.status()).isEqualTo(200);
        assertThat(resp.body().get("data").get("originalUrl").asText()).isEqualTo("https://example.com/plain");
    }

    @Test
    void unlockAppliesTheLinkStatusRulesAndHidesUnknownCodes() throws Exception {
        String disabled = createWithPassword(owner, "https://example.com/d", "pw-1234").body().get("data").get("shortCode").asText();
        api.call("PUT", "/api/v1/urls/" + disabled + "/disable", owner, null);
        String blocked = createWithPassword(owner, "https://example.com/b", "pw-1234").body().get("data").get("shortCode").asText();
        api.call("PUT", "/api/v1/admin/urls/" + blocked + "/block", api.loginAsAdmin(), "{\"reason\":\"test\"}");
        String deleted = createWithPassword(owner, "https://example.com/x", "pw-1234").body().get("data").get("shortCode").asText();
        api.call("DELETE", "/api/v1/urls/" + deleted, owner, null);

        assertThat(unlock(disabled, "pw-1234", uniqueIp()).status()).isEqualTo(404);
        Resp blockedResp = unlock(blocked, "pw-1234", uniqueIp());
        assertThat(blockedResp.status()).isEqualTo(403);
        assertThat(blockedResp.code()).isEqualTo("ERR_406");
        assertThat(unlock(deleted, "pw-1234", uniqueIp()).status()).isEqualTo(410);
        assertThat(unlock("missing" + shortId(), "pw", uniqueIp()).status()).isEqualTo(404);
        assertThat(unlock("admin", "pw", uniqueIp()).status()).isEqualTo(404); // mã dành riêng
        assertThat(unlock("ab", "pw", uniqueIp()).status()).isEqualTo(404);    // quá ngắn
    }

    @Test
    void unlockValidatesTheRequestBody() throws Exception {
        String code = createWithPassword(owner, "https://example.com/v", "pw-1234").body().get("data").get("shortCode").asText();

        Resp blank = api.call("POST", "/api/v1/public/urls/" + code + "/unlock", null, "{\"password\":\"\"}",
                Map.of("X-Forwarded-For", uniqueIp()));
        Resp huge = api.call("POST", "/api/v1/public/urls/" + code + "/unlock", null,
                "{\"password\":\"" + "a".repeat(101) + "\"}", Map.of("X-Forwarded-For", uniqueIp()));

        assertThat(blank.status()).isEqualTo(400);
        assertThat(huge.status()).isEqualTo(400);
    }

    // ===========================
    //  PASSWORD: brute-force protection
    // ===========================

    @Test
    void guessingPasswordsIsRateLimitedPerLinkAndIp() throws Exception {
        String code = createWithPassword(owner, "https://example.com/brute", "right-pw")
                .body().get("data").get("shortCode").asText();
        String attacker = uniqueIp();

        for (int i = 0; i < 10; i++) {
            Resp guess = unlock(code, "guess-" + i, attacker);
            assertThat(guess.status()).as("guess %d", i).isEqualTo(403);
        }
        Resp limited = unlock(code, "guess-11", attacker);

        assertThat(limited.status()).isEqualTo(429);
        assertThat(limited.code()).isEqualTo("ERR_500");
        assertThat(Long.parseLong(limited.header("Retry-After"))).isBetween(1L, 900L);
        // Ngay cả mật khẩu đúng cũng bị chặn trong thời gian khoá
        assertThat(unlock(code, "right-pw", attacker).status()).isEqualTo(429);
        // Khách hợp lệ ở IP khác vẫn mở được
        assertThat(unlock(code, "right-pw", uniqueIp()).status()).isEqualTo(200);
    }

    @Test
    void anIpCannotSweepManyProtectedLinksEither() throws Exception {
        String[] codes = new String[4];
        for (int i = 0; i < codes.length; i++) {
            codes[i] = createWithPassword(owner, "https://example.com/sweep/" + i, "right-pw")
                    .body().get("data").get("shortCode").asText();
        }
        String attacker = uniqueIp();

        // 3 link × 10 lần = 30 lần thử: đúng bằng giới hạn theo IP, mỗi link vừa chạm giới hạn theo (link, IP)
        for (int link = 0; link < 3; link++) {
            for (int attempt = 0; attempt < 10; attempt++) {
                assertThat(unlock(codes[link], "guess", attacker).status()).isEqualTo(403);
            }
        }
        // Link thứ tư còn nguyên ngân sách của riêng nó nhưng IP này đã hết ngân sách chung
        Resp limited = unlock(codes[3], "guess", attacker);

        assertThat(limited.status()).isEqualTo(429);
    }

    // ===========================
    //  PASSWORD: lifecycle
    // ===========================

    @Test
    void settingChangingAndRemovingAPasswordTakesEffectImmediately() throws Exception {
        String code = api.createUrl(owner, "https://example.com/lifecycle");
        assertThat(api.call("GET", "/" + code, null, null).status()).isEqualTo(302); // nạp cache

        Resp set = api.call("PUT", "/api/v1/urls/" + code + "/password", owner, "{\"password\":\"first-pw\"}");
        assertThat(set.status()).isEqualTo(200);
        assertThat(set.body().get("data").get("passwordProtected").asBoolean()).isTrue();
        assertThat(api.call("GET", "/" + code, null, null).status()).isEqualTo(403);
        assertThat(unlock(code, "first-pw", uniqueIp()).status()).isEqualTo(200);

        api.call("PUT", "/api/v1/urls/" + code + "/password", owner, "{\"password\":\"second-pw\"}");
        assertThat(unlock(code, "first-pw", uniqueIp()).status()).isEqualTo(403);
        assertThat(unlock(code, "second-pw", uniqueIp()).status()).isEqualTo(200);

        Resp removed = api.call("DELETE", "/api/v1/urls/" + code + "/password", owner, null);
        assertThat(removed.status()).isEqualTo(200);
        assertThat(removed.body().get("data").get("passwordProtected").asBoolean()).isFalse();
        assertThat(api.call("GET", "/" + code, null, null).status()).isEqualTo(302);
        assertThat(api.call("GET", "/api/v1/urls/" + code, owner, null).body().get("data").get("passwordProtected").asBoolean())
                .isFalse();
        // Gỡ lần hai vẫn ổn (idempotent)
        assertThat(api.call("DELETE", "/api/v1/urls/" + code + "/password", owner, null).status()).isEqualTo(200);
    }

    @Test
    void passwordsOutsideTheAllowedLengthAreRejected() throws Exception {
        String code = api.createUrl(owner, "https://example.com/lengths");

        for (String bad : new String[]{"abc", "", "  ", "x".repeat(65)}) {
            Resp set = api.call("PUT", "/api/v1/urls/" + code + "/password", owner, "{\"password\":\"" + bad + "\"}");
            assertThat(set.status()).as("set '%s'", bad).isEqualTo(400);
            assertThat(set.code()).isEqualTo("ERR_001");
        }
        Resp create = createWithPassword(owner, "https://example.com/y", "abc");
        assertThat(create.status()).isEqualTo(400);
        assertThat(api.call("GET", "/" + code, null, null).status()).isEqualTo(302); // vẫn không có mật khẩu
    }

    @Test
    void onlyTheOwnerCanManageThePasswordAndBlockedLinksAreFrozen() throws Exception {
        String code = createWithPassword(owner, "https://example.com/mine", "owner-pw")
                .body().get("data").get("shortCode").asText();

        assertThat(api.call("PUT", "/api/v1/urls/" + code + "/password", other, "{\"password\":\"hijack-pw\"}").status()).isEqualTo(404);
        assertThat(api.call("DELETE", "/api/v1/urls/" + code + "/password", other, null).status()).isEqualTo(404);
        assertThat(api.call("PUT", "/api/v1/urls/" + code + "/password", null, "{\"password\":\"x-pass\"}").status()).isEqualTo(401);
        assertThat(unlock(code, "owner-pw", uniqueIp()).status()).isEqualTo(200); // mật khẩu cũ vẫn đúng

        api.call("PUT", "/api/v1/admin/urls/" + code + "/block", api.loginAsAdmin(), "{\"reason\":\"abuse\"}");
        Resp frozenSet = api.call("PUT", "/api/v1/urls/" + code + "/password", owner, "{\"password\":\"new-pass\"}");
        Resp frozenRemove = api.call("DELETE", "/api/v1/urls/" + code + "/password", owner, null);
        assertThat(frozenSet.status()).isEqualTo(403);
        assertThat(frozenSet.code()).isEqualTo("ERR_401");
        assertThat(frozenRemove.status()).isEqualTo(403);
    }

    @Test
    void aProtectedLinkThatIsDeletedIsGoneNotPasswordProtected() throws Exception {
        String code = createWithPassword(owner, "https://example.com/del", "pw-1234").body().get("data").get("shortCode").asText();
        api.call("DELETE", "/api/v1/urls/" + code, owner, null);

        assertThat(api.call("GET", "/" + code, null, null).status()).isEqualTo(410);
    }

    // ===========================
    //  QR CODE
    // ===========================

    @Test
    void theQrCodeIsAScannablePngOfTheShortUrl() throws Exception {
        String code = api.createUrl(owner, "https://example.com/qr");

        BinaryResp qr = api.getBytes("/api/v1/urls/" + code + "/qr", owner);

        assertThat(qr.status()).isEqualTo(200);
        assertThat(qr.header("Content-Type")).isEqualTo("image/png");
        assertThat(qr.header("Cache-Control")).contains("private").contains("max-age=3600");
        assertThat(QrDecoder.decode(qr.body())).isEqualTo("http://localhost:8080/" + code);
        assertThat(QrDecoder.image(qr.body()).getWidth()).isEqualTo(320); // kích thước mặc định
    }

    @Test
    void theQrCodeOfAnAliasEncodesTheAliasUrl() throws Exception {
        String alias = "qr-" + shortId();
        createWithAlias(owner, "https://example.com/qr-alias", alias);

        BinaryResp qr = api.getBytes("/api/v1/urls/" + alias + "/qr?size=200", owner);

        assertThat(QrDecoder.decode(qr.body())).isEqualTo("http://localhost:8080/" + alias);
    }

    @Test
    void theQrSizeIsClampedToTheSupportedRange() throws Exception {
        String code = api.createUrl(owner, "https://example.com/qr-size");

        assertThat(QrDecoder.image(api.getBytes("/api/v1/urls/" + code + "/qr?size=10", owner).body()).getWidth())
                .isEqualTo(128);
        assertThat(QrDecoder.image(api.getBytes("/api/v1/urls/" + code + "/qr?size=100000", owner).body()).getWidth())
                .isEqualTo(1024);
        assertThat(QrDecoder.image(api.getBytes("/api/v1/urls/" + code + "/qr?size=500", owner).body()).getWidth())
                .isEqualTo(500);
        assertThat(api.call("GET", "/api/v1/urls/" + code + "/qr?size=abc", owner, null).status()).isEqualTo(400);
    }

    @Test
    void theQrCodeIsOnlyAvailableToTheOwnerOfAnExistingLink() throws Exception {
        String code = api.createUrl(owner, "https://example.com/qr-private");

        assertThat(api.getBytes("/api/v1/urls/" + code + "/qr", other).status()).isEqualTo(404);
        assertThat(api.getBytes("/api/v1/urls/" + code + "/qr", null).status()).isEqualTo(401);
        assertThat(api.getBytes("/api/v1/urls/doesnotexist1/qr", owner).status()).isEqualTo(404);

        api.call("DELETE", "/api/v1/urls/" + code, owner, null);
        assertThat(api.getBytes("/api/v1/urls/" + code + "/qr", owner).status()).isEqualTo(404);
    }

    // ===========================
    //  HELPERS
    // ===========================

    private Resp createWithAlias(String token, String url, String alias) throws Exception {
        return api.call("POST", "/api/v1/urls", token, "{\"originalUrl\":\"" + url + "\",\"alias\":\"" + alias + "\"}");
    }

    private Resp createWithPassword(String token, String url, String password) throws Exception {
        return api.call("POST", "/api/v1/urls", token, "{\"originalUrl\":\"" + url + "\",\"password\":\"" + password + "\"}");
    }

    private Resp unlock(String code, String password, String ip) throws Exception {
        return api.call("POST", "/api/v1/public/urls/" + code + "/unlock", null,
                "{\"password\":\"" + password + "\"}", Map.of("X-Forwarded-For", ip));
    }

    /** IP giả lập duy nhất cho mỗi lần gọi để bộ đếm rate limit (dùng chung Redis) không đè nhau. */
    private String uniqueIp() {
        long bits = UUID.randomUUID().getMostSignificantBits();
        return "203.0." + ((bits >>> 8) & 0xff) + "." + (bits & 0xff);
    }

    private String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
}
