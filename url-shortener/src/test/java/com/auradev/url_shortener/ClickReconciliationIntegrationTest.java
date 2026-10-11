package com.auradev.url_shortener;

import com.auradev.url_shortener.entity.Url;
import com.auradev.url_shortener.enums.UrlStatusEnum;
import com.auradev.url_shortener.event.ClickEvent;
import com.auradev.url_shortener.repository.UrlRepository;
import com.auradev.url_shortener.service.ClickIngestService;
import com.auradev.url_shortener.service.ClickReconciliationService;
import com.auradev.url_shortener.service.ClickReconciliationService.CounterMismatch;
import com.auradev.url_shortener.service.ClickReconciliationService.Result;
import com.auradev.url_shortener.support.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test đối soát bộ đếm click trên Postgres thật.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ClickReconciliationIntegrationTest {

    private static final String CHROME =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";
    private static final int LIMIT = 100_000;

    @Autowired private UrlRepository               urlRepository;
    @Autowired private ClickIngestService          ingestService;
    @Autowired private ClickReconciliationService  reconciliationService;
    @Autowired private JdbcTemplate                jdbc;

    @Test
    void consistentDataHasNoMismatchesForTheLink() {
        String code = linkWithClicks(3);

        Result result = reconciliationService.reconcile(LocalDate.now(), 2, LIMIT, false);

        assertThat(mismatchFor(result, code)).isNull();
        assertThat(result.dayMismatches()).isZero();
    }

    @Test
    void aDriftedCounterIsReportedButLeftAloneWhenRepairIsOff() {
        String code = linkWithClicks(3);
        jdbc.update("UPDATE urls SET click_count = 999 WHERE short_code = ?", code);

        Result result = reconciliationService.reconcile(LocalDate.now(), 2, LIMIT, false);

        CounterMismatch mismatch = mismatchFor(result, code);
        assertThat(mismatch).isNotNull();
        assertThat(mismatch.counter()).isEqualTo(999);
        assertThat(mismatch.statsTotal()).isEqualTo(3);
        assertThat(result.repaired()).isZero();
        assertThat(clickCount(code)).isEqualTo(999);
    }

    @Test
    void aDriftedCounterIsRepairedFromTheDailyStats() {
        String code = linkWithClicks(3);
        String untouched = linkWithClicks(2);
        jdbc.update("UPDATE urls SET click_count = 999 WHERE short_code = ?", code);

        Result result = reconciliationService.reconcile(LocalDate.now(), 2, LIMIT, true);

        assertThat(result.repaired()).isGreaterThanOrEqualTo(1);
        assertThat(clickCount(code)).isEqualTo(3);
        assertThat(clickCount(untouched)).isEqualTo(2);
        assertThat(mismatchFor(reconciliationService.reconcile(LocalDate.now(), 2, LIMIT, false), code)).isNull();
    }

    @Test
    void aCounterWithoutAnyStatsIsRepairedToZero() {
        String code = link();
        jdbc.update("UPDATE urls SET click_count = 7 WHERE short_code = ?", code);

        reconciliationService.reconcile(LocalDate.now(), 2, LIMIT, true);

        assertThat(clickCount(code)).isZero();
    }

    @Test
    void theReportIsCappedAtTheLimitAndSaysSo() {
        String first = linkWithClicks(1);
        String second = linkWithClicks(1);
        jdbc.update("UPDATE urls SET click_count = 50 WHERE short_code IN (?, ?)", first, second);

        try {
            Result result = reconciliationService.reconcile(LocalDate.now(), 2, 1, false);

            assertThat(result.counterMismatches()).hasSize(1);
            assertThat(result.truncated()).isTrue();
        } finally {
            reconciliationService.reconcile(LocalDate.now(), 2, LIMIT, true); // dọn: trả lại dữ liệu nhất quán
        }
    }

    @Test
    void aDailyStatThatDisagreesWithTheRawClicksIsDetectedInTheWindowOnly() {
        String code = linkWithClicks(2);
        jdbc.update("UPDATE url_daily_stats SET clicks = clicks + 5 WHERE url_id = (SELECT id FROM urls WHERE short_code = ?)", code);
        try {
            Result recent = reconciliationService.reconcile(LocalDate.now(), 2, LIMIT, false);
            // Cửa sổ nằm hoàn toàn trong tương lai không chứa ngày của link này
            Result elsewhere = reconciliationService.reconcile(LocalDate.now().plusDays(30), 2, LIMIT, false);

            assertThat(recent.dayMismatches()).isGreaterThanOrEqualTo(1);
            assertThat(elsewhere.dayMismatches()).isZero();
        } finally {
            jdbc.update("UPDATE url_daily_stats SET clicks = clicks - 5 WHERE url_id = (SELECT id FROM urls WHERE short_code = ?)", code);
        }
        assertThat(reconciliationService.reconcile(LocalDate.now(), 2, LIMIT, false).dayMismatches()).isZero();
    }

    // ===========================
    //  HELPERS
    // ===========================

    private String link() {
        String code = "r" + UUID.randomUUID().toString().replace("-", "").substring(0, 9);
        urlRepository.save(Url.builder()
                .shortCode(code)
                .originalUrl("https://example.com/" + code)
                .status(UrlStatusEnum.ACTIVE)
                .build());
        return code;
    }

    private String linkWithClicks(int clicks) {
        String code = link();
        List<ClickEvent> events = new java.util.ArrayList<>();
        for (int i = 0; i < clicks; i++) {
            events.add(new ClickEvent(UUID.randomUUID().toString(), code, LocalDateTime.now(), "visitor-" + i, null, CHROME));
        }
        ingestService.ingest(events);
        return code;
    }

    private CounterMismatch mismatchFor(Result result, String code) {
        return result.counterMismatches().stream().filter(m -> m.shortCode().equals(code)).findFirst().orElse(null);
    }

    private long clickCount(String code) {
        Long count = jdbc.queryForObject("SELECT click_count FROM urls WHERE short_code = ?", Long.class, code);
        return count == null ? 0 : count;
    }
}
