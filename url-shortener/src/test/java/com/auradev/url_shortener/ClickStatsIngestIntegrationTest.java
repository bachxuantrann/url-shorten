package com.auradev.url_shortener;

import com.auradev.url_shortener.entity.Url;
import com.auradev.url_shortener.enums.UrlStatusEnum;
import com.auradev.url_shortener.event.ClickEvent;
import com.auradev.url_shortener.repository.UrlRepository;
import com.auradev.url_shortener.service.ClickIngestService;
import com.auradev.url_shortener.support.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kiểm tra các bộ đếm theo ngày được cập nhật đúng và idempotent cùng lúc với url_clicks, trên Postgres thật.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ClickStatsIngestIntegrationTest {

    private static final String CHROME =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";
    private static final String GOOGLEBOT = "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)";

    @Autowired private UrlRepository      urlRepository;
    @Autowired private ClickIngestService ingestService;
    @Autowired private JdbcTemplate       jdbc;
    @Autowired private DataSource         dataSource;

    // ===========================
    //  CÁC BỘ ĐẾM THEO NGÀY
    // ===========================

    @Test
    void clicksAndUniqueVisitorsAreCountedPerDay() {
        String code = link();
        LocalDateTime today = LocalDateTime.now();

        ingestService.ingest(List.of(
                event(code, today, "visitor-a", CHROME),
                event(code, today, "visitor-a", CHROME),   // cùng khách, cùng ngày: không tăng unique
                event(code, today, "visitor-b", CHROME)));

        assertThat(daily(code, today.toLocalDate())).containsEntry("clicks", 3L)
                .containsEntry("bot_clicks", 0L).containsEntry("unique_visitors", 2L);
        assertThat(clickCount(code)).isEqualTo(3);
    }

    @Test
    void aVisitorSeenInAnEarlierBatchIsNotCountedAsNewAgainOnTheSameDay() {
        String code = link();
        LocalDateTime now = LocalDateTime.now();

        ingestService.ingest(List.of(event(code, now, "visitor-a", CHROME)));
        ingestService.ingest(List.of(event(code, now, "visitor-a", CHROME), event(code, now, "visitor-b", CHROME)));

        assertThat(daily(code, now.toLocalDate())).containsEntry("clicks", 3L).containsEntry("unique_visitors", 2L);
    }

    @Test
    void theSameVisitorOnTwoDaysIsUniqueOnEachDay() {
        String code = link();
        LocalDateTime today = LocalDateTime.now();
        LocalDateTime yesterday = today.minusDays(1);

        ingestService.ingest(List.of(event(code, today, "visitor-a", CHROME), event(code, yesterday, "visitor-a", CHROME)));

        assertThat(daily(code, today.toLocalDate())).containsEntry("unique_visitors", 1L);
        assertThat(daily(code, yesterday.toLocalDate())).containsEntry("unique_visitors", 1L);
    }

    @Test
    void botsCountAsClicksAndBotClicksButNeverAsUniqueVisitors() {
        String code = link();
        LocalDateTime now = LocalDateTime.now();

        ingestService.ingest(List.of(event(code, now, "visitor-a", CHROME), event(code, now, "bot-visitor", GOOGLEBOT)));

        assertThat(daily(code, now.toLocalDate())).containsEntry("clicks", 2L)
                .containsEntry("bot_clicks", 1L).containsEntry("unique_visitors", 1L);
        assertThat(visitorRows(code)).isEqualTo(1);
    }

    @Test
    void eventsWithoutAVisitorHashAreNotUnique() {
        String code = link();
        LocalDateTime now = LocalDateTime.now();

        ingestService.ingest(List.of(event(code, now, null, CHROME), event(code, now, null, CHROME)));

        assertThat(daily(code, now.toLocalDate())).containsEntry("clicks", 2L).containsEntry("unique_visitors", 0L);
    }

    @Test
    void redeliveredEventsChangeNothing() {
        String code = link();
        LocalDateTime now = LocalDateTime.now();
        List<ClickEvent> batch = List.of(event(code, now, "visitor-a", CHROME), event(code, now, "visitor-b", GOOGLEBOT));

        ingestService.ingest(batch);
        int again = ingestService.ingest(batch);

        assertThat(again).isZero();
        assertThat(daily(code, now.toLocalDate())).containsEntry("clicks", 2L)
                .containsEntry("bot_clicks", 1L).containsEntry("unique_visitors", 1L);
        assertThat(clickCount(code)).isEqualTo(2);
    }

    @Test
    void theLinkCounterAlwaysEqualsTheSumOfItsDailyStats() {
        String code = link();
        LocalDateTime now = LocalDateTime.now();

        ingestService.ingest(List.of(event(code, now, "a", CHROME), event(code, now.minusDays(2), "b", CHROME),
                event(code, now.minusDays(40), "c", GOOGLEBOT)));
        ingestService.ingest(List.of(event(code, now, "d", CHROME)));

        Long sum = jdbc.queryForObject("""
                SELECT sum(s.clicks) FROM url_daily_stats s JOIN urls u ON u.id = s.url_id WHERE u.short_code = ?
                """, Long.class, code);
        assertThat(sum).isEqualTo(4);
        assertThat(clickCount(code)).isEqualTo(4);
    }

    @Test
    void aBatchAcrossManyLinksAndDaysUpdatesEveryRow() {
        LocalDateTime now = LocalDateTime.now();
        List<ClickEvent> events = new ArrayList<>();
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            String code = link();
            codes.add(code);
            events.add(event(code, now, "v1", CHROME));
            events.add(event(code, now.minusDays(1), "v1", CHROME));
        }

        assertThat(ingestService.ingest(events)).isEqualTo(24);

        for (String code : codes) {
            assertThat(daily(code, now.toLocalDate())).containsEntry("clicks", 1L).containsEntry("unique_visitors", 1L);
            assertThat(daily(code, now.minusDays(1).toLocalDate())).containsEntry("clicks", 1L);
            assertThat(clickCount(code)).isEqualTo(2);
        }
    }

    // ===========================
    //  GHI ĐỒNG THỜI
    // ===========================

    @Test
    void concurrentBatchesWithOverlappingVisitorsStayExact() throws Exception {
        String code = link();
        LocalDateTime now = LocalDateTime.now();
        int threads = 4;
        int rounds = 5;
        int visitors = 60;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
                start.await();
                for (int r = 0; r < rounds; r++) {
                    List<ClickEvent> batch = new ArrayList<>();
                    for (int v = 0; v < visitors; v++) {
                        // Mọi thread đều gửi cùng 60 khách, nhưng event khác nhau
                        batch.add(event(code, now, "visitor-" + v, CHROME));
                    }
                    ingestService.ingest(batch);
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();

        long expectedClicks = (long) threads * rounds * visitors;
        assertThat(daily(code, now.toLocalDate())).containsEntry("clicks", expectedClicks)
                .containsEntry("unique_visitors", (long) visitors);
        assertThat(clickCount(code)).isEqualTo(expectedClicks);
        assertThat(visitorRows(code)).isEqualTo(visitors);
    }

    // ===========================
    //  BACKFILL (V9)
    // ===========================

    @Test
    void theBackfillScriptRebuildsTheSameStatsFromRawClicks() throws Exception {
        String code = link();
        LocalDateTime now = LocalDateTime.now();
        ingestService.ingest(List.of(
                event(code, now, "a", CHROME), event(code, now, "a", CHROME), event(code, now, "b", GOOGLEBOT),
                event(code, now.minusDays(3), "a", CHROME), event(code, now.minusDays(3), "c", CHROME)));
        List<Map<String, Object>> before = dailyRows(code);

        // Dựng lại toàn bộ từ url_clicks bằng đúng script migration V9
        jdbc.execute("TRUNCATE url_daily_stats, url_daily_visitors");
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V9__backfill_daily_stats.sql"));
        }

        assertThat(dailyRows(code)).isEqualTo(before);
        assertThat(before).hasSize(2);
    }

    // ===========================
    //  HELPERS
    // ===========================

    private String link() {
        String code = "s" + UUID.randomUUID().toString().replace("-", "").substring(0, 9);
        urlRepository.save(Url.builder()
                .shortCode(code)
                .originalUrl("https://example.com/" + code)
                .status(UrlStatusEnum.ACTIVE)
                .build());
        return code;
    }

    private ClickEvent event(String code, LocalDateTime at, String visitor, String userAgent) {
        return new ClickEvent(UUID.randomUUID().toString(), code, at, visitor, null, userAgent);
    }

    private Map<String, Object> daily(String code, LocalDate day) {
        return jdbc.queryForMap("""
                SELECT s.clicks, s.bot_clicks, s.unique_visitors FROM url_daily_stats s
                JOIN urls u ON u.id = s.url_id WHERE u.short_code = ? AND s.stat_date = ?
                """, code, java.sql.Date.valueOf(day));
    }

    private List<Map<String, Object>> dailyRows(String code) {
        return jdbc.queryForList("""
                SELECT s.stat_date, s.clicks, s.bot_clicks, s.unique_visitors FROM url_daily_stats s
                JOIN urls u ON u.id = s.url_id WHERE u.short_code = ? ORDER BY s.stat_date
                """, code);
    }

    private long visitorRows(String code) {
        Long count = jdbc.queryForObject("""
                SELECT count(*) FROM url_daily_visitors v JOIN urls u ON u.id = v.url_id WHERE u.short_code = ?
                """, Long.class, code);
        return count == null ? 0 : count;
    }

    private long clickCount(String code) {
        Long count = jdbc.queryForObject("SELECT click_count FROM urls WHERE short_code = ?", Long.class, code);
        return count == null ? 0 : count;
    }
}
