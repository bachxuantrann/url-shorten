package com.auradev.url_shortener;

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
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Đổi vai trò user end-to-end: cấp/gỡ quyền admin, không tự đổi mình, không gỡ admin cuối cùng, quyền truy cập, và
 * hiệu lực khi đăng nhập lại (role nằm trong JWT).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminRoleChangeIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private ApiClient api;
    private String admin;
    private String adminId;

    @BeforeEach
    void setUp() throws Exception {
        api = new ApiClient(port);
        admin = api.loginAsAdmin();
        adminId = meId(admin);
    }

    @Test
    void adminCanPromoteAUserAndTheNewTokenGetsAdminAccess() throws Exception {
        UserRef user = newUser();
        assertThat(api.call("GET", "/api/v1/admin/users", user.token, null).status()).isEqualTo(403);

        Resp resp = setRole(admin, user.id, "ROLE_ADMIN");

        assertThat(resp.status()).isEqualTo(200);
        assertThat(roles(resp.body().get("data"))).containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_USER");
        // Role nằm trong JWT: token cũ vẫn là user thường, đăng nhập lại mới có quyền
        assertThat(api.call("GET", "/api/v1/admin/users", user.token, null).status()).isEqualTo(403);
        String fresh = api.loginAs(user.username);
        assertThat(api.call("GET", "/api/v1/admin/users", fresh, null).status()).isEqualTo(200);
        assertThat(roles(api.call("GET", "/api/v1/admin/users/" + user.id, admin, null).body().get("data")))
                .containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_USER");
    }

    @Test
    void adminCanDemoteAnotherAdmin() throws Exception {
        UserRef user = newUser();
        setRole(admin, user.id, "ROLE_ADMIN");

        Resp resp = setRole(admin, user.id, "ROLE_USER");

        assertThat(resp.status()).isEqualTo(200);
        assertThat(roles(resp.body().get("data"))).containsExactly("ROLE_USER");
        String fresh = api.loginAs(user.username);
        assertThat(api.call("GET", "/api/v1/admin/users", fresh, null).status()).isEqualTo(403);
    }

    @Test
    void settingTheSameRoleChangesNothing() throws Exception {
        UserRef user = newUser();

        Resp resp = setRole(admin, user.id, "ROLE_USER");

        assertThat(resp.status()).isEqualTo(200);
        assertThat(roles(resp.body().get("data"))).containsExactly("ROLE_USER");
    }

    @Test
    void anAdminCannotChangeTheirOwnRole() throws Exception {
        Resp resp = setRole(admin, adminId, "ROLE_USER");

        assertThat(resp.status()).isEqualTo(403);
        assertThat(resp.code()).isEqualTo("ERR_107");
        assertThat(roles(api.call("GET", "/api/v1/admin/users/" + adminId, admin, null).body().get("data")))
                .contains("ROLE_ADMIN");
    }

    @Test
    void theLastActiveAdminCannotBeRemovedEvenWithAStaleAdminToken() throws Exception {
        UserRef other = newUser();
        setRole(admin, other.id, "ROLE_ADMIN");
        String otherToken = api.loginAs(other.username);
        // Các test khác có thể để lại admin phụ trong DB dùng chung: gỡ hết để chỉ còn admin gốc và "other"
        Resp admins = api.call("GET", "/api/v1/admin/users?role=ROLE_ADMIN&size=100", admin, null);
        assertThat(admins.status()).as(admins.body().toString()).isEqualTo(200);
        for (JsonNode u : admins.body().get("data").get("content")) {
            String id = u.get("id").asText();
            if (!id.equals(adminId) && !id.equals(other.id)) {
                assertThat(setRole(otherToken, id, "ROLE_USER").status()).isEqualTo(200);
            }
        }
        try {
            // "other" gỡ admin gốc (còn lại chính "other"), rồi admin gốc dùng token cũ (vẫn mang ROLE_ADMIN) gỡ nốt "other"
            assertThat(setRole(otherToken, adminId, "ROLE_USER").status()).isEqualTo(200);

            Resp resp = setRole(admin, other.id, "ROLE_USER");

            assertThat(resp.status()).isEqualTo(409);
            assertThat(resp.code()).isEqualTo("ERR_108");
            assertThat(roles(api.call("GET", "/api/v1/admin/users/" + other.id, otherToken, null).body().get("data")))
                    .contains("ROLE_ADMIN");
        } finally {
            setRole(otherToken, adminId, "ROLE_ADMIN"); // trả lại trạng thái cho các test khác
            // Admin gốc dùng chung với FoundationIntegrationTest (kiểm tra audit của lần seed): trả lại cả cột audit
            jdbcTemplate.update("UPDATE users SET updated_by = 'system' WHERE username = 'admin'");
        }
        assertThat(roles(api.call("GET", "/api/v1/admin/users/" + adminId, otherToken, null).body().get("data")))
                .contains("ROLE_ADMIN");
    }

    @Test
    void onlyAdminsCanChangeRoles() throws Exception {
        UserRef user = newUser();
        UserRef target = newUser();

        assertThat(setRole(user.token, target.id, "ROLE_ADMIN").status()).isEqualTo(403);
        assertThat(api.call("PUT", "/api/v1/admin/users/" + target.id + "/role", null, "{\"role\":\"ROLE_ADMIN\"}").status())
                .isEqualTo(401);
    }

    @Test
    void invalidRequestsAndUnknownUsersAreRejected() throws Exception {
        UserRef target = newUser();
        String path = "/api/v1/admin/users/" + target.id + "/role";

        assertThat(api.call("PUT", path, admin, "{}").status()).isEqualTo(400);
        assertThat(api.call("PUT", path, admin, "{\"role\":\"ROLE_GOD\"}").status()).isEqualTo(400);
        assertThat(api.call("PUT", path, admin, "{not json").status()).isEqualTo(400);
        Resp unknown = setRole(admin, UUID.randomUUID().toString(), "ROLE_ADMIN");
        assertThat(unknown.status()).isEqualTo(404);
        assertThat(unknown.code()).isEqualTo("ERR_100");
    }

    @Test
    void userListCanBeFilteredByRoleOrStatusAloneAndKeywordWildcardsAreLiteral() throws Exception {
        UserRef user = newUser();
        setRole(admin, user.id, "ROLE_ADMIN");

        // Trước đây lọc không kèm từ khoá làm Postgres báo lower(bytea) does not exist (500)
        Resp byRole = api.call("GET", "/api/v1/admin/users?role=ROLE_ADMIN&size=100", admin, null);
        assertThat(byRole.status()).isEqualTo(200);
        assertThat(ids(byRole)).contains(user.id, adminId);
        Resp byStatus = api.call("GET", "/api/v1/admin/users?status=ACTIVE&size=100", admin, null);
        assertThat(byStatus.status()).isEqualTo(200);
        assertThat(ids(byStatus)).contains(user.id);
        Resp both = api.call("GET", "/api/v1/admin/users?status=ACTIVE&role=ROLE_USER&keyword=" + user.username, admin, null);
        assertThat(ids(both)).containsExactly(user.id);
        // % và _ là ký tự thường, không phải wildcard
        assertThat(ids(api.call("GET", "/api/v1/admin/users?keyword=%25", admin, null))).isEmpty();
    }

    // ===========================
    //  TIỆN ÍCH
    // ===========================

    private record UserRef(String id, String username, String token) {
    }

    private UserRef newUser() throws Exception {
        String token = api.registerAndLogin();
        String id = meId(token);
        String username = api.call("GET", "/api/v1/users/me", token, null).body().get("data").get("username").asText();
        return new UserRef(id, username, token);
    }

    private String meId(String token) throws Exception {
        return api.call("GET", "/api/v1/users/me", token, null).body().get("data").get("id").asText();
    }

    private Resp setRole(String token, String userId, String role) throws Exception {
        return api.call("PUT", "/api/v1/admin/users/" + userId + "/role", token, "{\"role\":\"" + role + "\"}");
    }

    private static List<String> ids(Resp resp) {
        List<String> result = new ArrayList<>();
        resp.body().get("data").get("content").forEach(u -> result.add(u.get("id").asText()));
        return result;
    }

    private static List<String> roles(JsonNode user) {
        List<String> result = new ArrayList<>();
        user.get("roles").forEach(r -> result.add(r.asText()));
        return result;
    }
}
