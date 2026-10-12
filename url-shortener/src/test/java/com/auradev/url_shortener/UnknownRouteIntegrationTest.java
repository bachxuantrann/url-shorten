package com.auradev.url_shortener;

import com.auradev.url_shortener.support.ApiClient;
import com.auradev.url_shortener.support.ApiClient.Resp;
import com.auradev.url_shortener.support.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Đường dẫn không tồn tại và sai phương thức HTTP trả lỗi của client (404/405) đúng khung {@code ApiResponse},
 * không rơi xuống handler tổng quát (500).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UnknownRouteIntegrationTest {

    @LocalServerPort
    private int port;

    private ApiClient api;
    private String user;

    @BeforeEach
    void setUp() throws Exception {
        api = new ApiClient(port);
        user = api.registerAndLogin();
    }

    @Test
    void anUnknownApiPathAnswers404WithTheStandardErrorBody() throws Exception {
        for (String path : new String[]{"/api/v1/does-not-exist", "/api/v1/urls/abc1234/nope/deeper", "/api/v1/admin/nothing"}) {
            Resp resp = api.call("GET", path, user, null);

            // /admin/** exige ROLE_ADMIN nên user thường nhận 403 trước khi tới MVC; hai đường dẫn kia tới được MVC
            if (path.startsWith("/api/v1/admin")) {
                assertThat(resp.status()).as(path).isEqualTo(403);
                continue;
            }
            assertThat(resp.status()).as(path).isEqualTo(404);
            assertThat(resp.code()).as(path).isEqualTo("ERR_002");
            assertThat(resp.body().get("success").asBoolean()).isFalse();
        }
    }

    @Test
    void anAdminHittingAnUnknownAdminPathGets404NotA500() throws Exception {
        Resp resp = api.call("GET", "/api/v1/admin/nothing", api.loginAsAdmin(), null);

        assertThat(resp.status()).isEqualTo(404);
        assertThat(resp.code()).isEqualTo("ERR_002");
    }

    @Test
    void theMessageFollowsTheAcceptLanguageOfTheClient() throws Exception {
        Resp resp = api.call("GET", "/api/v1/does-not-exist", user, null);

        // ApiClient gửi Accept-Language: en
        assertThat(resp.body().get("message").asText()).isEqualTo("The requested resource does not exist.");
    }

    @Test
    void aWrongMethodOnAnExistingEndpointAnswers405WithAnAllowHeader() throws Exception {
        Resp resp = api.call("DELETE", "/api/v1/users/me", user, null);

        assertThat(resp.status()).isEqualTo(405);
        assertThat(resp.code()).isEqualTo("ERR_003");
        assertThat(resp.header("Allow")).contains("GET").contains("PUT");
    }

    @Test
    void anonymousCallersStillGet401BeforeAnyOfThis() throws Exception {
        assertThat(api.call("GET", "/api/v1/does-not-exist", null, null).status()).isEqualTo(401);
        assertThat(api.call("DELETE", "/api/v1/users/me", null, null).status()).isEqualTo(401);
    }
}
