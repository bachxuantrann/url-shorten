package com.auradev.url_shortener;

import com.auradev.url_shortener.entity.Url;
import com.auradev.url_shortener.enums.UrlStatusEnum;
import com.auradev.url_shortener.repository.UrlRepository;
import com.auradev.url_shortener.support.ApiClient;
import com.auradev.url_shortener.support.ApiClient.Resp;
import com.auradev.url_shortener.support.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 4 end-to-end qua HTTP thật: link ẩn danh, rate limit, chặn URL đích nguy hiểm,
 * blocklist domain, chặn/gỡ chặn link bởi admin.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AbuseProtectionApiIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private UrlRepository urlRepository;

    private ApiClient api;
    private String owner;
    private String admin;

    @BeforeEach
    void setUp() throws Exception {
        api = new ApiClient(port);
        owner = api.registerAndLogin();
        admin = api.loginAsAdmin();
    }

    // ===========================
    //  ANONYMOUS LINKS
    // ===========================

    @Test
    void anonymousCreateReturns201AndTheLinkHasNoOwnerButRedirects() throws Exception {
        Resp resp = anonymousCreate("https://example.com/anon", uniqueIp());

        assertThat(resp.status()).isEqualTo(201);
        String code = resp.body().get("data").get("shortCode").asText();
        Url row = urlRepository.findByShortCode(code).orElseThrow();
        assertThat(row.getUserId()).isNull();
        assertThat(row.getCreatedBy()).isEqualTo("anonymous");
        assertThat(api.call("GET", "/" + code, null, null).status()).isEqualTo(302);
    }

    @Test
    void anonymousEndpointIgnoresAnInvalidBearerToken() throws Exception {
        Resp resp = api.call("POST", "/api/v1/public/urls", "not.a.jwt",
                "{\"originalUrl\":\"https://example.com/x\"}", ip(uniqueIp()));

        assertThat(resp.status()).isEqualTo(201);
    }

    @Test
    void anonymousCreateIsRateLimitedPerClientIp() throws Exception {
        String ip = uniqueIp();

        for (int i = 0; i < 10; i++) {
            assertThat(anonymousCreate("https://example.com/limit/" + i, ip).status()).as("request %d", i).isEqualTo(201);
        }
        Resp limited = anonymousCreate("https://example.com/limit/over", ip);

        assertThat(limited.status()).isEqualTo(429);
        assertThat(limited.code()).isEqualTo("ERR_500");
        assertThat(Long.parseLong(limited.header("Retry-After"))).isBetween(1L, 3600L);

        // Một IP khác không bị ảnh hưởng
        assertThat(anonymousCreate("https://example.com/other-ip", uniqueIp()).status()).isEqualTo(201);
    }

    @Test
    void rejectedAttemptsAlsoCountTowardsTheLimit() throws Exception {
        String ip = uniqueIp();

        for (int i = 0; i < 10; i++) {
            Resp bad = anonymousCreate("ftp://example.com/file", ip);
            assertThat(bad.status()).isEqualTo(400);
            assertThat(bad.code()).isEqualTo("ERR_404");
        }

        assertThat(anonymousCreate("https://example.com/good", ip).status()).isEqualTo(429);
    }

    @Test
    void anonymousCreateAppliesTheSameTargetRules() throws Exception {
        Resp resp = anonymousCreate("http://169.254.169.254/latest/meta-data/", uniqueIp());

        assertThat(resp.status()).isEqualTo(400);
        assertThat(resp.code()).isEqualTo("ERR_502");
    }

    // ===========================
    //  PER-USER RATE LIMIT
    // ===========================

    @Test
    void authenticatedCreateIsRateLimitedPerUser() throws Exception {
        for (int i = 0; i < 100; i++) {
            Resp ok = api.call("POST", "/api/v1/urls", owner, "{\"originalUrl\":\"https://example.com/u/" + i + "\"}");
            assertThat(ok.status()).as("request %d", i).isEqualTo(201);
        }

        Resp limited = api.call("POST", "/api/v1/urls", owner, "{\"originalUrl\":\"https://example.com/u/over\"}");

        assertThat(limited.status()).isEqualTo(429);
        assertThat(limited.code()).isEqualTo("ERR_500");
        assertThat(limited.header("Retry-After")).isNotNull();

        // Người dùng khác không bị ảnh hưởng
        assertThat(api.createUrl(api.registerAndLogin(), "https://example.com/other-user")).isNotBlank();
    }

    // ===========================
    //  TARGET POLICY
    // ===========================

    @Test
    void createRejectsLocalPrivateAndSelfTargets() throws Exception {
        for (String target : new String[]{
                "http://localhost:3000/x", "http://127.0.0.1/", "http://10.0.0.5/admin",
                "http://169.254.169.254/latest/meta-data/", "http://[::1]/", "http://intranet/",
                "https://printer.local/", "http://localhost:8080/abc"}) {
            Resp resp = api.call("POST", "/api/v1/urls", owner, "{\"originalUrl\":\"" + target + "\"}");
            assertThat(resp.status()).as(target).isEqualTo(400);
            assertThat(resp.code()).as(target).isEqualTo("ERR_502");
        }
    }

    @Test
    void updateAlsoEnforcesTheTargetPolicyAndKeepsTheOldTarget() throws Exception {
        String code = api.createUrl(owner, "https://example.com/original");

        Resp resp = api.call("PUT", "/api/v1/urls/" + code, owner, "{\"originalUrl\":\"http://127.0.0.1/evil\"}");

        assertThat(resp.status()).isEqualTo(400);
        assertThat(resp.code()).isEqualTo("ERR_502");
        assertThat(urlRepository.findByShortCode(code).orElseThrow().getOriginalUrl())
                .isEqualTo("https://example.com/original");
    }

    // ===========================
    //  DOMAIN BLOCKLIST
    // ===========================

    @Test
    void blockedDomainRejectsNewAndUpdatedLinksIncludingSubdomains() throws Exception {
        String domain = "phish" + shortId() + ".com";
        String existing = api.createUrl(owner, "https://example.com/fine");

        Resp added = addDomain(domain, "phishing", true);
        assertThat(added.status()).isEqualTo(201);
        assertThat(added.body().get("data").get("blockedLinks").asInt()).isZero();

        for (String target : new String[]{"https://" + domain + "/login", "http://a.b." + domain + "/x", "https://" + domain.toUpperCase() + "/Y"}) {
            Resp resp = api.call("POST", "/api/v1/urls", owner, "{\"originalUrl\":\"" + target + "\"}");
            assertThat(resp.status()).as(target).isEqualTo(400);
            assertThat(resp.code()).as(target).isEqualTo("ERR_503");
        }
        Resp update = api.call("PUT", "/api/v1/urls/" + existing, owner, "{\"originalUrl\":\"https://" + domain + "/x\"}");
        assertThat(update.status()).isEqualTo(400);
        assertThat(update.code()).isEqualTo("ERR_503");

        // Domain chỉ trùng hậu tố hoặc đứng sau không bị chặn nhầm
        assertThat(api.createUrl(owner, "https://not" + domain + "/x")).isNotBlank();
        assertThat(api.createUrl(owner, "https://" + domain + ".au/x")).isNotBlank();
    }

    @Test
    void addingADomainBlocksExistingLinksAndRedirectStopsImmediately() throws Exception {
        String domain = "shady" + shortId() + ".com";
        String active = api.createUrl(owner, "https://" + domain + "/a");
        String viaSubdomain = api.createUrl(owner, "https://www." + domain + "/b");
        String disabled = api.createUrl(owner, "https://" + domain + "/c");
        api.call("PUT", "/api/v1/urls/" + disabled + "/disable", owner, null);
        String unrelated = api.createUrl(owner, "https://example.com/unrelated");
        for (String code : new String[]{active, viaSubdomain, unrelated}) {
            assertThat(api.call("GET", "/" + code, null, null).status()).isEqualTo(302); // nạp cache
        }

        Resp added = addDomain(domain, "scam site", true);

        assertThat(added.body().get("data").get("blockedLinks").asInt()).isEqualTo(3);
        for (String code : new String[]{active, viaSubdomain}) {
            Resp redirect = api.call("GET", "/" + code, null, null);
            assertThat(redirect.status()).as(code).isEqualTo(403);
            assertThat(redirect.code()).as(code).isEqualTo("ERR_406");
        }
        assertThat(api.call("GET", "/" + unrelated, null, null).status()).isEqualTo(302);

        Resp mine = api.call("GET", "/api/v1/urls/" + active, owner, null);
        assertThat(mine.body().get("data").get("status").asText()).isEqualTo("BLOCKED");
        assertThat(mine.body().get("data").get("blockedReason").asText()).isEqualTo("Domain blocked: " + domain);
        assertThat(urlRepository.findByShortCode(disabled).orElseThrow().getStatus()).isEqualTo(UrlStatusEnum.BLOCKED);
        // Bulk update vẫn ghi người thao tác vào cột audit
        assertThat(urlRepository.findByShortCode(active).orElseThrow().getUpdatedBy()).isEqualTo("admin");
    }

    @Test
    void addingADomainWithoutBlockingExistingLinksLeavesThemAlone() throws Exception {
        String domain = "keep" + shortId() + ".com";
        String code = api.createUrl(owner, "https://" + domain + "/a");

        Resp added = addDomain(domain, null, false);

        assertThat(added.body().get("data").get("blockedLinks").asInt()).isZero();
        assertThat(api.call("GET", "/" + code, null, null).status()).isEqualTo(302);
        assertThat(api.call("POST", "/api/v1/urls", owner, "{\"originalUrl\":\"https://" + domain + "/new\"}").status())
                .isEqualTo(400);
    }

    @Test
    void duplicateAndInvalidDomainsAreRejected() throws Exception {
        String domain = "dup" + shortId() + ".com";
        assertThat(addDomain(domain, null, false).status()).isEqualTo(201);

        Resp duplicate = addDomain(domain.toUpperCase(), null, false);
        assertThat(duplicate.status()).isEqualTo(409);
        assertThat(duplicate.code()).isEqualTo("ERR_504");

        Resp invalid = addDomain("not a domain", null, false);
        assertThat(invalid.status()).isEqualTo(400);
        assertThat(invalid.code()).isEqualTo("ERR_506");

        Resp blank = api.call("POST", "/api/v1/admin/blocked-domains", admin, "{\"domain\":\"\"}");
        assertThat(blank.status()).isEqualTo(400);
        assertThat(blank.code()).isEqualTo("ERR_001");
    }

    @Test
    void blockedDomainsCanBeListedSearchedAndRemoved() throws Exception {
        String token = shortId();
        long id = addDomain("list" + token + ".com", "r1", false).body().get("data").get("id").asLong();
        addDomain("other" + token + ".com", "r2", false);

        Resp searched = api.call("GET", "/api/v1/admin/blocked-domains?keyword=list" + token, admin, null);
        assertThat(searched.status()).isEqualTo(200);
        assertThat(searched.body().get("data").get("totalElements").asLong()).isEqualTo(1);
        assertThat(searched.body().get("data").get("content").get(0).get("domain").asText()).isEqualTo("list" + token + ".com");

        Resp all = api.call("GET", "/api/v1/admin/blocked-domains?keyword=" + token + "&size=10", admin, null);
        assertThat(all.body().get("data").get("totalElements").asLong()).isEqualTo(2);

        assertThat(api.call("DELETE", "/api/v1/admin/blocked-domains/" + id, admin, null).status()).isEqualTo(200);
        Resp again = api.call("DELETE", "/api/v1/admin/blocked-domains/" + id, admin, null);
        assertThat(again.status()).isEqualTo(404);
        assertThat(again.code()).isEqualTo("ERR_505");

        // Sau khi gỡ khỏi blocklist, tạo link tới domain đó lại được
        assertThat(api.createUrl(owner, "https://list" + token + ".com/ok")).isNotBlank();
    }

    @Test
    void adminEndpointsRequireTheAdminRole() throws Exception {
        String body = "{\"domain\":\"nope.example\"}";

        assertThat(api.call("POST", "/api/v1/admin/blocked-domains", owner, body).status()).isEqualTo(403);
        assertThat(api.call("POST", "/api/v1/admin/blocked-domains", null, body).status()).isEqualTo(401);
        assertThat(api.call("GET", "/api/v1/admin/blocked-domains", owner, null).status()).isEqualTo(403);
        assertThat(api.call("PUT", "/api/v1/admin/urls/abcdefg/block", owner, "{\"reason\":\"x\"}").status()).isEqualTo(403);
        assertThat(api.call("PUT", "/api/v1/admin/urls/abcdefg/unblock", null, null).status()).isEqualTo(401);
        assertThat(api.call("GET", "/api/v1/admin/urls/abcdefg", owner, null).status()).isEqualTo(403);
    }

    // ===========================
    //  ADMIN BLOCK / UNBLOCK
    // ===========================

    @Test
    void adminBlocksAndUnblocksAnyLinkWithImmediateEffectOnRedirect() throws Exception {
        String code = api.createUrl(owner, "https://example.com/report-me");
        assertThat(api.call("GET", "/" + code, null, null).status()).isEqualTo(302); // nạp cache

        Resp blocked = api.call("PUT", "/api/v1/admin/urls/" + code + "/block", admin, "{\"reason\":\"phishing report\"}");
        assertThat(blocked.status()).isEqualTo(200);
        assertThat(blocked.body().get("data").get("status").asText()).isEqualTo("BLOCKED");
        assertThat(blocked.body().get("data").get("blockedReason").asText()).isEqualTo("phishing report");

        Resp redirect = api.call("GET", "/" + code, null, null);
        assertThat(redirect.status()).isEqualTo(403);
        assertThat(redirect.code()).isEqualTo("ERR_406");

        // Chủ link thấy trạng thái + lý do nhưng không tự gỡ được
        Resp mine = api.call("GET", "/api/v1/urls/" + code, owner, null);
        assertThat(mine.body().get("data").get("status").asText()).isEqualTo("BLOCKED");
        assertThat(mine.body().get("data").get("blockedReason").asText()).isEqualTo("phishing report");
        for (Resp attempt : new Resp[]{
                api.call("PUT", "/api/v1/urls/" + code, owner, "{\"originalUrl\":\"https://example.com/x\"}"),
                api.call("PUT", "/api/v1/urls/" + code + "/enable", owner, null),
                api.call("PUT", "/api/v1/urls/" + code + "/disable", owner, null),
                api.call("DELETE", "/api/v1/urls/" + code, owner, null)}) {
            assertThat(attempt.status()).isEqualTo(403);
            assertThat(attempt.code()).isEqualTo("ERR_401");
        }

        Resp unblocked = api.call("PUT", "/api/v1/admin/urls/" + code + "/unblock", admin, null);
        assertThat(unblocked.status()).isEqualTo(200);
        assertThat(unblocked.body().get("data").get("status").asText()).isEqualTo("ACTIVE");
        assertThat(unblocked.body().get("data").has("blockedReason")).isFalse();
        assertThat(api.call("GET", "/" + code, null, null).status()).isEqualTo(302);

        Resp again = api.call("PUT", "/api/v1/admin/urls/" + code + "/unblock", admin, null);
        assertThat(again.status()).isEqualTo(409);
        assertThat(again.code()).isEqualTo("ERR_402");
    }

    @Test
    void adminCanSeeTheOwnerOfALinkAndBlockAnAnonymousOne() throws Exception {
        String ownedCode = api.createUrl(owner, "https://example.com/owned");
        String anonCode = anonymousCreate("https://example.com/anon-block", uniqueIp())
                .body().get("data").get("shortCode").asText();

        Resp owned = api.call("GET", "/api/v1/admin/urls/" + ownedCode, admin, null);
        assertThat(owned.status()).isEqualTo(200);
        assertThat(owned.body().get("data").get("userId").asText()).isNotBlank();
        assertThat(owned.body().get("data").get("createdBy").asText()).startsWith("user_");

        Resp anon = api.call("GET", "/api/v1/admin/urls/" + anonCode, admin, null);
        assertThat(anon.body().get("data").has("userId")).isFalse();

        api.call("PUT", "/api/v1/admin/urls/" + anonCode + "/block", admin, "{\"reason\":\"spam\"}");
        assertThat(api.call("GET", "/" + anonCode, null, null).status()).isEqualTo(403);
    }

    @Test
    void blockRequiresAReasonAndAnExistingNonDeletedLink() throws Exception {
        String code = api.createUrl(owner, "https://example.com/needs-reason");

        Resp noReason = api.call("PUT", "/api/v1/admin/urls/" + code + "/block", admin, "{\"reason\":\"  \"}");
        assertThat(noReason.status()).isEqualTo(400);
        assertThat(noReason.code()).isEqualTo("ERR_001");

        assertThat(api.call("PUT", "/api/v1/admin/urls/doesnotexist1/block", admin, "{\"reason\":\"x\"}").status())
                .isEqualTo(404);

        api.call("DELETE", "/api/v1/urls/" + code, owner, null);
        Resp deleted = api.call("PUT", "/api/v1/admin/urls/" + code + "/block", admin, "{\"reason\":\"x\"}");
        assertThat(deleted.status()).isEqualTo(404);
    }

    @Test
    void blockingAgainJustUpdatesTheReason() throws Exception {
        String code = api.createUrl(owner, "https://example.com/reason");
        api.call("PUT", "/api/v1/admin/urls/" + code + "/block", admin, "{\"reason\":\"first\"}");

        Resp second = api.call("PUT", "/api/v1/admin/urls/" + code + "/block", admin, "{\"reason\":\"second\"}");

        assertThat(second.status()).isEqualTo(200);
        assertThat(second.body().get("data").get("blockedReason").asText()).isEqualTo("second");
    }

    // ===========================
    //  HELPERS
    // ===========================

    private Resp anonymousCreate(String originalUrl, String ip) throws Exception {
        return api.call("POST", "/api/v1/public/urls", null, "{\"originalUrl\":\"" + originalUrl + "\"}", ip(ip));
    }

    private Resp addDomain(String domain, String reason, boolean blockExisting) throws Exception {
        String body = "{\"domain\":\"" + domain + "\""
                + (reason == null ? "" : ",\"reason\":\"" + reason + "\"")
                + ",\"blockExistingLinks\":" + blockExisting + "}";
        return api.call("POST", "/api/v1/admin/blocked-domains", admin, body);
    }

    /** Tomcat tin X-Forwarded-For vì kết nối test đến từ loopback (proxy tin cậy). */
    private Map<String, String> ip(String address) {
        return Map.of("X-Forwarded-For", address);
    }

    /** IP giả lập duy nhất cho mỗi test để bộ đếm rate limit (dùng chung Redis) không đè nhau. */
    private String uniqueIp() {
        UUID id = UUID.randomUUID();
        long bits = id.getMostSignificantBits();
        return "198.51." + ((bits >>> 8) & 0xff) + "." + (bits & 0xff);
    }

    private String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
}
