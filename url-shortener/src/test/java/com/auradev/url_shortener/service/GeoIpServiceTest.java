package com.auradev.url_shortener.service;

import com.auradev.url_shortener.config.ClickProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class GeoIpServiceTest {

    private GeoIpService service(String path) throws Exception {
        ClickProperties properties = new ClickProperties();
        properties.setGeoipDatabase(path);
        return new GeoIpService(properties);
    }

    @Test
    void isDisabledAndReturnsNullWithoutADatabase() throws Exception {
        for (String path : new String[]{null, "", "  "}) {
            GeoIpService service = service(path);

            assertThat(service.isEnabled()).isFalse();
            assertThat(service.countryOf("8.8.8.8")).isNull();
        }
    }

    @Test
    void failsFastWhenTheConfiguredFileIsMissing() {
        assertThatThrownBy(() -> service("target/does-not-exist.mmdb"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does-not-exist.mmdb");
    }

    /**
     * Chạy với file thật: đặt biến môi trường {@code GEOIP_TEST_DB} trỏ tới một file GeoLite2-Country.mmdb
     * (hoặc file test của MaxMind). Không có thì test bị bỏ qua, repo không chứa file .mmdb.
     */
    @Test
    void looksUpARealDatabaseWhenOneIsProvided() throws Exception {
        String path = System.getenv("GEOIP_TEST_DB");
        assumeTrue(path != null && !path.isBlank(), "GEOIP_TEST_DB is not set");

        GeoIpService service = service(path);
        try {
            assertThat(service.isEnabled()).isTrue();
            assertThat(service.countryOf("8.8.8.8")).matches("[A-Z]{2}");
            assertThat(service.countryOf("127.0.0.1")).isNull();
            assertThat(service.countryOf("not-an-ip")).isNull();
        } finally {
            service.destroy();
        }
    }
}
