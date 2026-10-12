package com.auradev.url_shortener;

import com.auradev.url_shortener.support.ApiClient;
import com.auradev.url_shortener.support.ApiClient.Resp;
import com.auradev.url_shortener.support.TestcontainersConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Admin khoá tài khoản thì có hiệu lực NGAY: access token đã cấp bị từ chối ở request kế tiếp bằng 403 {@code ERR_104}
 * (trước đây sống tiếp tới khi hết hạn, tối đa 15 phút, rồi bị đăng xuất âm thầm), refresh và đăng nhập cũng báo 403 ERR_104,
 * và mở khoá thì đăng nhập lại được.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AccountLockIntegrationTest {

    @LocalServerPort
    private int port;

    private ApiClient api;
    private String admin;

    private String username;
    private String userId;
    private String accessToken;
    private String refreshToken;

    @BeforeEach
    void setUp() throws Exception {
        api = new ApiClient(port);
        admin = api.loginAsAdmin();

        username = "lock_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        Resp register = api.call("POST", "/api/v1/auth/register", null,
                "{\"username\":\"" + username + "\",\"email\":\"" + username + "@test.local\","
                        + "\"password\":\"" + ApiClient.PASSWORD + "\",\"fullName\":\"Lock Test\"}");
        assertThat(register.status()).isEqualTo(201);
        Resp login = login();
        assertThat(login.status()).isEqualTo(200);
        JsonNode data = login.body().get("data");
        accessToken = data.get("accessToken").asText();
        refreshToken = data.get("refreshToken").asText();
        userId = data.get("user").get("id").asText();
    }

    @Test
    void aLockedUsersExistingAccessTokenIsRejectedImmediatelyWith403() throws Exception {
        assertThat(api.call("GET", "/api/v1/users/me", accessToken, null).status()).isEqualTo(200);

        assertThat(api.call("PUT", "/api/v1/admin/users/" + userId + "/lock", admin, null).status()).isEqualTo(200);

        Resp resp = api.call("GET", "/api/v1/users/me", accessToken, null);
        assertThat(resp.status()).isEqualTo(403);
        assertThat(resp.code()).isEqualTo("ERR_104");
        assertThat(resp.body().get("success").asBoolean()).isFalse();
        // Mọi endpoint cần xác thực đều bị chặn, không chỉ /me
        assertThat(api.call("GET", "/api/v1/urls", accessToken, null).code()).isEqualTo("ERR_104");
        assertThat(api.call("POST", "/api/v1/urls", accessToken, "{\"originalUrl\":\"https://example.com/x\"}").status())
                .isEqualTo(403);
    }

    @Test
    void aLockedUserCannotRefreshOrLogInAndGetsTheBannedError() throws Exception {
        api.call("PUT", "/api/v1/admin/users/" + userId + "/lock", admin, null);

        Resp refresh = api.call("POST", "/api/v1/auth/refresh-token", null, "{\"refreshToken\":\"" + refreshToken + "\"}");
        Resp login = login();

        // Refresh cũng báo đúng lý do (người nghỉ lâu rồi quay lại vẫn biết tài khoản bị khoá, không bị đá ra âm thầm)
        assertThat(refresh.status()).isEqualTo(403);
        assertThat(refresh.code()).isEqualTo("ERR_104");
        assertThat(login.status()).isEqualTo(403);
        assertThat(login.code()).isEqualTo("ERR_104");
    }

    @Test
    void unlockingLetsTheUserLogInAgainWithAFreshWorkingToken() throws Exception {
        api.call("PUT", "/api/v1/admin/users/" + userId + "/lock", admin, null);
        assertThat(api.call("GET", "/api/v1/users/me", accessToken, null).status()).isEqualTo(403);

        assertThat(api.call("PUT", "/api/v1/admin/users/" + userId + "/unlock", admin, null).status()).isEqualTo(200);

        Resp login = login();
        assertThat(login.status()).isEqualTo(200);
        String fresh = login.body().get("data").get("accessToken").asText();
        assertThat(api.call("GET", "/api/v1/users/me", fresh, null).status()).isEqualTo(200);
        // Dấu khoá đã được gỡ nên cả token cũ (chưa hết hạn) dùng lại được
        assertThat(api.call("GET", "/api/v1/users/me", accessToken, null).status()).isEqualTo(200);
        // Refresh token cũ cũng dùng lại được (phiên không bị thu hồi, chỉ bị chặn trong lúc khoá)
        assertThat(api.call("POST", "/api/v1/auth/refresh-token", null, "{\"refreshToken\":\"" + refreshToken + "\"}")
                .status()).isEqualTo(200);
    }

    @Test
    void lockingOneUserDoesNotAffectOthersAndAdminsStayLoggedIn() throws Exception {
        String other = api.registerAndLogin();

        api.call("PUT", "/api/v1/admin/users/" + userId + "/lock", admin, null);

        assertThat(api.call("GET", "/api/v1/users/me", other, null).status()).isEqualTo(200);
        assertThat(api.call("GET", "/api/v1/admin/users", admin, null).status()).isEqualTo(200);
    }

    @Test
    void lockingTwiceAndUnlockingAnActiveUserAreHarmless() throws Exception {
        assertThat(api.call("PUT", "/api/v1/admin/users/" + userId + "/unlock", admin, null).status()).isEqualTo(200);
        assertThat(api.call("GET", "/api/v1/users/me", accessToken, null).status()).isEqualTo(200);

        assertThat(api.call("PUT", "/api/v1/admin/users/" + userId + "/lock", admin, null).status()).isEqualTo(200);
        assertThat(api.call("PUT", "/api/v1/admin/users/" + userId + "/lock", admin, null).status()).isEqualTo(200);

        assertThat(api.call("GET", "/api/v1/users/me", accessToken, null).status()).isEqualTo(403);
    }

    @Test
    void publicEndpointsStillWorkWithALockedUsersToken() throws Exception {
        api.call("PUT", "/api/v1/admin/users/" + userId + "/lock", admin, null);

        // Token của người bị khoá gửi kèm lên endpoint công khai (đang mở trang chủ) không gây lỗi
        Resp config = api.call("GET", "/api/v1/public/config", accessToken, null);

        assertThat(config.status()).isEqualTo(200);
    }

    private Resp login() throws Exception {
        return api.call("POST", "/api/v1/auth/login", null,
                "{\"username\":\"" + username + "\",\"password\":\"" + ApiClient.PASSWORD + "\"}");
    }
}
