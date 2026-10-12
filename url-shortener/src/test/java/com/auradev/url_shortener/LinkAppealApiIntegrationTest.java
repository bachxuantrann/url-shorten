package com.auradev.url_shortener;

import com.auradev.url_shortener.entity.Url;
import com.auradev.url_shortener.repository.LinkAppealRepository;
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
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kháng nghị link bị chặn end-to-end qua HTTP thật: chủ link gửi, admin chấp nhận hoặc từ chối, trạng thái hiện ra
 * ở danh sách link của chủ, gỡ chặn xoá cache redirect, và số câu lệnh SQL không phụ thuộc số link trong trang.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LinkAppealApiIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private UrlRepository urlRepository;

    @Autowired
    private LinkAppealRepository appealRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

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
    //  GỬI KHÁNG NGHỊ
    // ===========================

    @Test
    void ownerAppealsABlockedLinkAndSeesItWaiting() throws Exception {
        String code = blockedLink(owner);

        Resp resp = appeal(owner, code, "This is my own landing page, not phishing.");

        assertThat(resp.status()).isEqualTo(201);
        JsonNode data = resp.body().get("data");
        assertThat(data.get("status").asText()).isEqualTo("BLOCKED");
        assertThat(data.get("appeal").get("status").asText()).isEqualTo("OPEN");
        assertThat(data.get("appeal").get("message").asText()).isEqualTo("This is my own landing page, not phishing.");

        // GET một link và danh sách đều thấy trạng thái kháng nghị
        assertThat(getLink(owner, code).get("appeal").get("status").asText()).isEqualTo("OPEN");
        assertThat(listedLink(owner, code).get("appeal").get("status").asText()).isEqualTo("OPEN");
    }

    @Test
    void appealingALinkThatIsNotBlockedIsAConflict() throws Exception {
        String code = api.createUrl(owner, "https://example.com/" + UUID.randomUUID());

        Resp resp = appeal(owner, code, "please");

        assertThat(resp.status()).isEqualTo(409);
        assertThat(resp.code()).isEqualTo("ERR_402");
    }

    @Test
    void onlyTheOwnerCanAppealAndUnknownLinksAreNotFound() throws Exception {
        String code = blockedLink(owner);
        String stranger = api.registerAndLogin();

        assertThat(appeal(stranger, code, "not mine").status()).isEqualTo(404);
        assertThat(appeal(owner, "doesnotexist1", "x").status()).isEqualTo(404);
        // Không có kháng nghị nào được tạo cho link của người khác
        assertThat(findAppeal("OPEN", code)).isNull();
    }

    @Test
    void secondAppealWhileOneIsWaitingIsRejected() throws Exception {
        String code = blockedLink(owner);
        assertThat(appeal(owner, code, "first").status()).isEqualTo(201);

        Resp second = appeal(owner, code, "second");

        assertThat(second.status()).isEqualTo(409);
        assertThat(second.code()).isEqualTo("ERR_412");
    }

    @Test
    void simultaneousAppealsCreateExactlyOne() throws Exception {
        String code = blockedLink(owner);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                String message = "attempt " + i;
                Callable<Integer> task = () -> appeal(owner, code, message).status();
                results.add(pool.submit(task));
            }
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : results) {
                statuses.add(f.get());
            }
            assertThat(statuses).filteredOn(s -> s == 201).hasSize(1);
            assertThat(statuses).filteredOn(s -> s == 409).hasSize(3);
        } finally {
            pool.shutdownNow();
        }
        assertThat(openAppealsFor(code)).hasSize(1);
    }

    @Test
    void invalidMessagesAreRejectedWith400() throws Exception {
        String code = blockedLink(owner);

        assertThat(appeal(owner, code, "").status()).isEqualTo(400);
        assertThat(appeal(owner, code, "   ").status()).isEqualTo(400);
        assertThat(appeal(owner, code, "x".repeat(501)).status()).isEqualTo(400);
        assertThat(api.call("POST", "/api/v1/urls/" + code + "/appeal", owner, "{not json").status()).isEqualTo(400);
        assertThat(api.call("POST", "/api/v1/urls/" + code + "/appeal", owner, "{}").status()).isEqualTo(400);
        assertThat(openAppealsFor(code)).isEmpty();
        // Đúng 500 ký tự vẫn được nhận
        assertThat(appeal(owner, code, "x".repeat(500)).status()).isEqualTo(201);
    }

    @Test
    void appealsAreRateLimitedPerUser() throws Exception {
        String user = api.registerAndLogin();
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            codes.add(blockedLink(user));
        }

        for (int i = 0; i < 5; i++) {
            assertThat(appeal(user, codes.get(i), "appeal " + i).status()).isEqualTo(201);
        }
        Resp limited = appeal(user, codes.get(5), "one too many");

        assertThat(limited.status()).isEqualTo(429);
        assertThat(limited.header("Retry-After")).isNotNull();
    }

    @Test
    void appealEndpointRequiresLogin() throws Exception {
        String code = blockedLink(owner);

        assertThat(api.call("POST", "/api/v1/urls/" + code + "/appeal", null, "{\"message\":\"x\"}").status())
                .isEqualTo(401);
    }

    // ===========================
    //  ADMIN XEM XÉT
    // ===========================

    @Test
    void adminSeesTheAppealWithLinkReasonAndOwner() throws Exception {
        String code = blockedLink(owner, "Looks like phishing");
        appeal(owner, code, "I can prove I own this site");

        JsonNode row = findAppeal("OPEN", code);

        assertThat(row).isNotNull();
        assertThat(row.get("message").asText()).isEqualTo("I can prove I own this site");
        assertThat(row.get("blockedReason").asText()).isEqualTo("Looks like phishing");
        assertThat(row.get("linkStatus").asText()).isEqualTo("BLOCKED");
        assertThat(row.get("owner").asText()).startsWith("user_");
        assertThat(row.get("originalUrl").asText()).startsWith("https://example.com/");
        assertThat(row.get("shortUrl").asText()).endsWith("/" + code);
        assertThat(row.get("status").asText()).isEqualTo("OPEN");
    }

    @Test
    void acceptingUnblocksTheLinkAndTheRedirectWorksAgain() throws Exception {
        String code = blockedLink(owner);
        appeal(owner, code, "please unblock");
        // Nạp cache redirect với trạng thái BLOCKED để chứng minh việc gỡ chặn xoá được nó
        assertThat(api.call("GET", "/" + code, null, null).status()).isEqualTo(403);
        long id = findAppeal("OPEN", code).get("id").asLong();

        Resp resp = api.call("PUT", "/api/v1/admin/appeals/" + id + "/accept", admin, null);

        assertThat(resp.status()).isEqualTo(200);
        assertThat(resp.body().get("data").get("status").asText()).isEqualTo("ACCEPTED");
        assertThat(resp.body().get("data").get("linkStatus").asText()).isEqualTo("ACTIVE");
        assertThat(api.call("GET", "/" + code, null, null).status()).isEqualTo(302);

        JsonNode link = getLink(owner, code);
        assertThat(link.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(link.has("appeal")).isFalse();
        assertThat(link.has("blockedReason")).isFalse();
        assertThat(findAppeal("OPEN", code)).isNull();
        assertThat(findAppeal("ACCEPTED", code)).isNotNull();
    }

    @Test
    void rejectingKeepsTheLinkBlockedAndShowsTheResponse() throws Exception {
        String code = blockedLink(owner);
        appeal(owner, code, "please");
        long id = findAppeal("OPEN", code).get("id").asLong();

        Resp resp = api.call("PUT", "/api/v1/admin/appeals/" + id + "/reject", admin,
                "{\"response\":\"The page still asks for bank passwords.\"}");

        assertThat(resp.status()).isEqualTo(200);
        assertThat(resp.body().get("data").get("status").asText()).isEqualTo("REJECTED");
        JsonNode link = getLink(owner, code);
        assertThat(link.get("status").asText()).isEqualTo("BLOCKED");
        assertThat(link.get("appeal").get("status").asText()).isEqualTo("REJECTED");
        assertThat(link.get("appeal").get("response").asText()).isEqualTo("The page still asks for bank passwords.");
        assertThat(api.call("GET", "/" + code, null, null).status()).isEqualTo(403);
        assertThat(findAppeal("REJECTED", code).get("response").asText()).contains("bank passwords");
    }

    @Test
    void rejectingWithoutAResponseWorksAndTooLongAResponseIsRefused() throws Exception {
        String code = blockedLink(owner);
        appeal(owner, code, "please");
        long id = findAppeal("OPEN", code).get("id").asLong();

        assertThat(api.call("PUT", "/api/v1/admin/appeals/" + id + "/reject", admin,
                "{\"response\":\"" + "x".repeat(256) + "\"}").status()).isEqualTo(400);
        assertThat(findAppeal("OPEN", code)).isNotNull(); // nothing changed

        assertThat(api.call("PUT", "/api/v1/admin/appeals/" + id + "/reject", admin, null).status()).isEqualTo(200);
        JsonNode appeal = getLink(owner, code).get("appeal");
        assertThat(appeal.get("status").asText()).isEqualTo("REJECTED");
        assertThat(appeal.has("response")).isFalse();
    }

    @Test
    void anAppealCanOnlyBeResolvedOnce() throws Exception {
        String code = blockedLink(owner);
        appeal(owner, code, "please");
        long id = findAppeal("OPEN", code).get("id").asLong();
        assertThat(api.call("PUT", "/api/v1/admin/appeals/" + id + "/reject", admin, null).status()).isEqualTo(200);

        Resp again = api.call("PUT", "/api/v1/admin/appeals/" + id + "/accept", admin, null);

        assertThat(again.status()).isEqualTo(409);
        assertThat(again.code()).isEqualTo("ERR_414");
        // Lần chấp nhận thứ hai không làm gì cả: link vẫn bị chặn
        assertThat(getLink(owner, code).get("status").asText()).isEqualTo("BLOCKED");
    }

    @Test
    void unknownAppealIdIsNotFound() throws Exception {
        Resp resp = api.call("PUT", "/api/v1/admin/appeals/999999999/accept", admin, null);

        assertThat(resp.status()).isEqualTo(404);
        assertThat(resp.code()).isEqualTo("ERR_413");
        assertThat(api.call("PUT", "/api/v1/admin/appeals/999999999/reject", admin, null).status()).isEqualTo(404);
    }

    @Test
    void ownerCanAppealAgainAfterARejection() throws Exception {
        String code = blockedLink(owner);
        appeal(owner, code, "first");
        long first = findAppeal("OPEN", code).get("id").asLong();
        api.call("PUT", "/api/v1/admin/appeals/" + first + "/reject", admin, "{\"response\":\"no\"}");

        Resp second = appeal(owner, code, "second, with more evidence");

        assertThat(second.status()).isEqualTo(201);
        JsonNode appeal = second.body().get("data").get("appeal");
        assertThat(appeal.get("status").asText()).isEqualTo("OPEN");
        assertThat(appeal.get("message").asText()).isEqualTo("second, with more evidence");
    }

    @Test
    void unblockingDirectlyAnswersTheWaitingAppeal() throws Exception {
        String code = blockedLink(owner);
        appeal(owner, code, "please");

        assertThat(api.call("PUT", "/api/v1/admin/urls/" + code + "/unblock", admin, null).status()).isEqualTo(200);

        assertThat(findAppeal("OPEN", code)).isNull();
        assertThat(findAppeal("ACCEPTED", code)).isNotNull();
        assertThat(getLink(owner, code).has("appeal")).isFalse();
    }

    @Test
    void aRejectionFromAnEarlierBlockIsNotShownAfterTheLinkIsBlockedAgain() throws Exception {
        String code = blockedLink(owner);
        appeal(owner, code, "please");
        long id = findAppeal("OPEN", code).get("id").asLong();
        api.call("PUT", "/api/v1/admin/appeals/" + id + "/reject", admin, "{\"response\":\"no\"}");
        assertThat(getLink(owner, code).get("appeal").get("status").asText()).isEqualTo("REJECTED");

        api.call("PUT", "/api/v1/admin/urls/" + code + "/unblock", admin, null);
        block(code, "Blocked again for a new reason");

        JsonNode link = getLink(owner, code);
        assertThat(link.get("status").asText()).isEqualTo("BLOCKED");
        assertThat(link.has("appeal")).isFalse();
        assertThat(appeal(owner, code, "new appeal for the new block").status()).isEqualTo(201);
    }

    @Test
    void appealEndpointsAreForAdminsOnly() throws Exception {
        assertThat(api.call("GET", "/api/v1/admin/appeals", owner, null).status()).isEqualTo(403);
        assertThat(api.call("PUT", "/api/v1/admin/appeals/1/accept", owner, null).status()).isEqualTo(403);
        assertThat(api.call("PUT", "/api/v1/admin/appeals/1/reject", owner, null).status()).isEqualTo(403);
        assertThat(api.call("GET", "/api/v1/admin/appeals", null, null).status()).isEqualTo(401);
    }

    // ===========================
    //  KHÔNG N+1
    // ===========================

    @Test
    void statementsDoNotGrowWithTheNumberOfBlockedLinksInAPage() throws Exception {
        String few = api.registerAndLogin();
        String many = api.registerAndLogin();
        blockWithAppeals(few, 2);
        blockWithAppeals(many, 12);

        long forFew = statementsFor(() -> api.call("GET", "/api/v1/urls?size=50", few, null));
        long forMany = statementsFor(() -> api.call("GET", "/api/v1/urls?size=50", many, null));

        assertThat(forMany).as("owner list, 2 vs 12 blocked links").isEqualTo(forFew);
        Resp page = api.call("GET", "/api/v1/urls?size=50", many, null);
        assertThat(page.body().get("data").get("content")).hasSize(12)
                .allSatisfy(l -> assertThat(l.get("appeal").get("status").asText()).isEqualTo("OPEN"));
    }

    @Test
    void adminListStatementsDoNotGrowWithTheNumberOfAppeals() throws Exception {
        // Cả hai lần đo đều là trang đầy (Spring bỏ câu đếm khi trang đầu chưa đầy), nên cùng một đường chạy
        blockWithAppeals(api.registerAndLogin(), 4);
        long twoRows = statementsFor(() -> api.call("GET", "/api/v1/admin/appeals?size=2", admin, null));
        for (int i = 0; i < 4; i++) {
            blockWithAppeals(api.registerAndLogin(), 3);
        }

        long fifteenRows = statementsFor(() -> api.call("GET", "/api/v1/admin/appeals?size=15", admin, null));

        assertThat(fifteenRows).as("admin list, 2 vs 15 rows (link and owner joined in one query)").isEqualTo(twoRows);
        Resp page = api.call("GET", "/api/v1/admin/appeals?size=15", admin, null);
        assertThat(page.body().get("data").get("content")).hasSize(15);
    }

    // ===========================
    //  TIỆN ÍCH
    // ===========================

    private String blockedLink(String token) throws Exception {
        return blockedLink(token, "Reported as spam");
    }

    private String blockedLink(String token, String reason) throws Exception {
        String code = api.createUrl(token, "https://example.com/appeal/" + UUID.randomUUID());
        block(code, reason);
        return code;
    }

    private void block(String code, String reason) throws Exception {
        Resp resp = api.call("PUT", "/api/v1/admin/urls/" + code + "/block", admin, "{\"reason\":\"" + reason + "\"}");
        assertThat(resp.status()).isEqualTo(200);
    }

    /** Tạo {@code count} link bị chặn có kháng nghị đang chờ; ghi thẳng qua repository để không chạm giới hạn 5 lần/giờ. */
    private void blockWithAppeals(String token, int count) throws Exception {
        for (int i = 0; i < count; i++) {
            String code = blockedLink(token);
            Url url = urlRepository.findByShortCode(code).orElseThrow();
            int n = i;
            Integer stored = transactionTemplate.execute(status -> appealRepository.insertIfNoneOpen(
                    url.getId(), url.getUserId(), "appeal " + n, LocalDateTime.now(), "test"));
            assertThat(stored).isEqualTo(1);
        }
    }

    private Resp appeal(String token, String code, String message) throws Exception {
        return api.call("POST", "/api/v1/urls/" + code + "/appeal", token, "{\"message\":\"" + message + "\"}");
    }

    private JsonNode getLink(String token, String code) throws Exception {
        Resp resp = api.call("GET", "/api/v1/urls/" + code, token, null);
        assertThat(resp.status()).isEqualTo(200);
        return resp.body().get("data");
    }

    private JsonNode listedLink(String token, String code) throws Exception {
        Resp resp = api.call("GET", "/api/v1/urls?size=100&keyword=" + code, token, null);
        for (JsonNode l : resp.body().get("data").get("content")) {
            if (l.get("shortCode").asText().equals(code)) {
                return l;
            }
        }
        throw new AssertionError("link " + code + " not in the list");
    }

    /** Dòng kháng nghị của link trong danh sách admin theo trạng thái, hoặc null nếu không có. */
    private JsonNode findAppeal(String status, String code) throws Exception {
        for (int page = 0; ; page++) {
            Resp resp = api.call("GET", "/api/v1/admin/appeals?status=" + status + "&size=100&page=" + page, admin, null);
            assertThat(resp.status()).isEqualTo(200);
            JsonNode data = resp.body().get("data");
            for (JsonNode row : data.get("content")) {
                if (row.get("shortCode").asText().equals(code)) {
                    return row;
                }
            }
            if (!data.get("hasNext").asBoolean()) {
                return null;
            }
        }
    }

    private List<JsonNode> openAppealsFor(String code) throws Exception {
        List<JsonNode> rows = new ArrayList<>();
        JsonNode row = findAppeal("OPEN", code);
        if (row != null) {
            rows.add(row);
        }
        return rows;
    }

    /** Số câu lệnh SQL (Hibernate) mà một lần gọi API chạy; test chạy tuần tự và job nền bị tắt nên số này ổn định. */
    private long statementsFor(Callable<Resp> call) throws Exception {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        try {
            statistics.clear();
            Resp resp = call.call();
            assertThat(resp.status()).isEqualTo(200);
            return statistics.getPrepareStatementCount();
        } finally {
            statistics.setStatisticsEnabled(false);
        }
    }
}
