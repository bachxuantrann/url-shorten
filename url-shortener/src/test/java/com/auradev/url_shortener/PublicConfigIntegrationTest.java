package com.auradev.url_shortener;

import com.auradev.url_shortener.support.ApiClient;
import com.auradev.url_shortener.support.ApiClient.Resp;
import com.auradev.url_shortener.support.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

import java.time.Clock;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Giao diện cần múi giờ của máy chủ để quy đổi hạn dùng (API trả {@code LocalDateTime} không kèm múi giờ).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PublicConfigIntegrationTest {

    @LocalServerPort
    private int port;

    private ApiClient api;

    @BeforeEach
    void setUp() {
        api = new ApiClient(port);
    }

    @Test
    void theServerTimezoneIsPublicAndMatchesTheClockUsedForLinkExpiry() throws Exception {
        Resp resp = api.call("GET", "/api/v1/public/config", null, null);

        assertThat(resp.status()).isEqualTo(200);
        assertThat(resp.body().get("data").get("timezone").asText()).isEqualTo(Clock.systemDefaultZone().getZone().getId());
    }

    @Test
    void theReportedTimezoneIsAValidIanaZoneThatTheBrowserCanUse() throws Exception {
        String zone = api.call("GET", "/api/v1/public/config", null, null).body().get("data").get("timezone").asText();

        // Intl.DateTimeFormat của trình duyệt chấp nhận mã vùng IANA; ZoneId.of không ném lỗi chứng tỏ cùng định dạng
        assertThat(ZoneId.of(zone).getId()).isEqualTo(zone);
        assertThat(zone).doesNotContain(" ");
    }

    @Test
    void theResponseMayBeCachedBrieflyByTheBrowser() throws Exception {
        Resp resp = api.call("GET", "/api/v1/public/config", null, null);

        assertThat(resp.header("Cache-Control")).contains("max-age=600").contains("public");
    }
}
