package com.auradev.url_shortener;

import com.auradev.url_shortener.entity.User;
import com.auradev.url_shortener.repository.UserRepository;
import com.auradev.url_shortener.support.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kiểm tra nền tảng Phase 0: Flyway, schema khớp entity, audit timestamps, actuator health.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FoundationIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserRepository userRepository;

    @Test
    void flywayAppliesV1Migration() {
        Integer applied = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '1' AND success = TRUE",
                Integer.class);
        assertThat(applied).isEqualTo(1);
    }

    @Test
    void adminAccountIsSeededWithAuditTimestamps() {
        User admin = userRepository.findByUsername("admin").orElseThrow();

        assertThat(admin.getCreatedAt()).isNotNull();
        assertThat(admin.getUpdatedAt()).isNotNull();
        assertThat(admin.getRoles()).hasSize(2);
        // DataInitializer chạy ngoài request → auditor là "system"
        assertThat(admin.getCreatedBy()).isEqualTo("system");
        assertThat(admin.getUpdatedBy()).isEqualTo("system");
    }

    @Test
    void auditingRecordsAuthenticatedUsernameAndKeepsCreatedByOnUpdate() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("alice", null, List.of()));
        try {
            User saved = userRepository.saveAndFlush(newUser("audit_user"));
            assertThat(saved.getCreatedBy()).isEqualTo("alice");
            assertThat(saved.getUpdatedBy()).isEqualTo("alice");

            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken("bob", null, List.of()));
            saved.setFullName("Changed");
            User updated = userRepository.saveAndFlush(saved);

            assertThat(updated.getCreatedBy()).isEqualTo("alice");
            assertThat(updated.getUpdatedBy()).isEqualTo("bob");
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void auditingFallsBackToAnonymousForAnonymousAuthentication() {
        SecurityContextHolder.getContext().setAuthentication(
                new AnonymousAuthenticationToken("key", "anonymousUser",
                        List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
        try {
            User saved = userRepository.saveAndFlush(newUser("anon_user"));
            assertThat(saved.getCreatedBy()).isEqualTo("anonymous");
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private User newUser(String username) {
        return User.builder()
                .username(username)
                .email(username + "@test.local")
                .password("x")
                .build();
    }

    @Test
    void actuatorHealthIsPublicAndUp() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/health")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"");
    }

    @Test
    void otherActuatorEndpointsAreNotExposed() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/env")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isIn(401, 403, 404);
    }
}
