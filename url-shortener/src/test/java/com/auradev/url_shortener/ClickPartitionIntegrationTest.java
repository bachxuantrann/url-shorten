package com.auradev.url_shortener;

import com.auradev.url_shortener.service.ClickPartitionService;
import com.auradev.url_shortener.service.ClickPartitionService.Result;
import com.auradev.url_shortener.support.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test bảo trì phân vùng {@code url_clicks} trên Postgres thật.
 *
 * <p>Chỉ dùng năm ở xa (2030+, 2018) để không đụng vào phân vùng tháng thật mà test khác đang ghi vào,
 * và lệnh xoá luôn chạy với ngày thật nên chỉ phân vùng cũ hơn 13 tháng tính từ hôm nay bị xoá.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ClickPartitionIntegrationTest {

    /** Phải trùng {@code ClickPartitionServiceImpl.ADVISORY_LOCK_KEY}. */
    private static final long ADVISORY_LOCK_KEY = 7_066_001L;

    @Autowired private ClickPartitionService partitionService;
    @Autowired private JdbcTemplate          jdbc;
    @Autowired private DataSource            dataSource;

    @Test
    void createsTheCurrentAndUpcomingMonthsAndIsIdempotent() {
        Result first = partitionService.maintain(LocalDate.of(2030, 11, 20), 3, 0);

        assertThat(first.ran()).isTrue();
        assertThat(first.created()).containsExactly("url_clicks_y2030m11", "url_clicks_y2030m12", "url_clicks_y2031m01");
        assertThat(partitionNames()).contains("url_clicks_y2030m11", "url_clicks_y2030m12", "url_clicks_y2031m01");

        Result second = partitionService.maintain(LocalDate.of(2030, 11, 20), 3, 0);

        assertThat(second.created()).isEmpty();
    }

    @Test
    void createdPartitionHasTheRightBoundsAndAcceptsRows() {
        partitionService.maintain(LocalDate.of(2032, 2, 10), 1, 0);
        jdbc.update("INSERT INTO urls (short_code, original_url, status, created_at, updated_at, created_by, updated_by)"
                + " VALUES ('part-bounds', 'https://example.com', 'ACTIVE', now(), now(), 't', 't')");
        Long urlId = jdbc.queryForObject("SELECT id FROM urls WHERE short_code = 'part-bounds'", Long.class);

        jdbc.update("INSERT INTO url_clicks (event_id, url_id, clicked_at, device_type, browser, os)"
                + " VALUES (gen_random_uuid(), ?, '2032-02-29 23:59:59', 'DESKTOP', 'Chrome', 'Linux')", urlId);

        String partition = jdbc.queryForObject(
                "SELECT tableoid::regclass::text FROM url_clicks WHERE url_id = ?", String.class, urlId);
        assertThat(partition).isEqualTo("url_clicks_y2032m02");
    }

    @Test
    void dropsOnlyPartitionsOlderThanTheRetentionAndKeepsDefaultAndRecentOnes() {
        partitionService.maintain(LocalDate.of(2018, 3, 10), 2, 0); // 2018-03, 2018-04
        assertThat(partitionNames()).contains("url_clicks_y2018m03", "url_clicks_y2018m04");

        Result result = partitionService.maintain(LocalDate.now(), 1, 13);

        assertThat(result.dropped()).contains("url_clicks_y2018m03", "url_clicks_y2018m04");
        List<String> names = partitionNames();
        assertThat(names).doesNotContain("url_clicks_y2018m03", "url_clicks_y2018m04");
        assertThat(names).contains("url_clicks_default");
        String currentMonth = String.format("url_clicks_y%04dm%02d", LocalDate.now().getYear(), LocalDate.now().getMonthValue());
        assertThat(names).contains(currentMonth);
    }

    @Test
    void retentionOfZeroKeepsEverything() {
        partitionService.maintain(LocalDate.of(2017, 5, 10), 1, 0);

        Result result = partitionService.maintain(LocalDate.now(), 1, 0);

        assertThat(result.dropped()).isEmpty();
        assertThat(partitionNames()).contains("url_clicks_y2017m05");
    }

    @Test
    void anotherInstanceHoldingTheLockMakesThisRunSkip() throws Exception {
        try (Connection other = dataSource.getConnection()) {
            lock(other, "SELECT pg_advisory_lock(" + ADVISORY_LOCK_KEY + ")");
            try {
                Result skipped = partitionService.maintain(LocalDate.of(2035, 1, 5), 1, 0);

                assertThat(skipped.ran()).isFalse();
                assertThat(partitionNames()).doesNotContain("url_clicks_y2035m01");
            } finally {
                lock(other, "SELECT pg_advisory_unlock(" + ADVISORY_LOCK_KEY + ")");
            }
        }

        assertThat(partitionService.maintain(LocalDate.of(2035, 1, 5), 1, 0).ran()).isTrue();
    }

    private void lock(Connection connection, String sql) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.execute();
        }
    }

    private List<String> partitionNames() {
        return jdbc.queryForList("""
                SELECT c.relname FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid
                WHERE i.inhparent = 'url_clicks'::regclass
                """, String.class);
    }
}
