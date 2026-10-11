package com.auradev.url_shortener;

import com.auradev.url_shortener.entity.Url;
import com.auradev.url_shortener.entity.User;
import com.auradev.url_shortener.enums.UrlStatusEnum;
import com.auradev.url_shortener.exception.AppException;
import com.auradev.url_shortener.exception.ErrorCode;
import com.auradev.url_shortener.job.UrlExpirationJob;
import com.auradev.url_shortener.repository.UrlRepository;
import com.auradev.url_shortener.repository.UserRepository;
import com.auradev.url_shortener.service.RedirectService;
import com.auradev.url_shortener.service.UrlExpirationService;
import com.auradev.url_shortener.support.TestcontainersConfiguration;
import com.auradev.url_shortener.utils.UrlCacheService;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Job chuyển link hết hạn sang EXPIRED: đúng đối tượng, có audit, xoá cache, an toàn khi chạy song song.
 * Job tự động (scheduler) bị đẩy sang PT24H trong cấu hình surefire nên chỉ chạy khi test gọi trực tiếp.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class UrlExpirationIntegrationTest {

    @Autowired
    private UrlExpirationJob job;

    @Autowired
    private UrlExpirationService expirationService;

    @Autowired
    private UrlRepository urlRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UrlCacheService urlCacheService;

    @Autowired
    private RedirectService redirectService;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private User owner;

    @BeforeEach
    void createOwnerAndClearBacklog() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        owner = userRepository.save(User.builder()
                .username("exp_" + suffix).email("exp_" + suffix + "@test.local").password("x").build());
        // Xử lý hết link quá hạn do các test khác để lại, để mỗi test bắt đầu từ trạng thái sạch
        job.expireDueLinks();
    }

    @Test
    void onlyActiveLinksPastTheirExpiryAreMovedToExpired() {
        Url due1    = link(UrlStatusEnum.ACTIVE, LocalDateTime.now().minusHours(2));
        Url due2    = link(UrlStatusEnum.ACTIVE, LocalDateTime.now().minusSeconds(1));
        Url future  = link(UrlStatusEnum.ACTIVE, LocalDateTime.now().plusDays(1));
        Url forever = link(UrlStatusEnum.ACTIVE, null);
        Url disabled = link(UrlStatusEnum.DISABLED, LocalDateTime.now().minusHours(1));
        Url blocked  = link(UrlStatusEnum.BLOCKED, LocalDateTime.now().minusHours(1));
        Url deleted  = link(UrlStatusEnum.DELETED, LocalDateTime.now().minusHours(1));

        int moved = job.expireDueLinks();

        assertThat(moved).isEqualTo(2);
        assertThat(statusOf(due1)).isEqualTo(UrlStatusEnum.EXPIRED);
        assertThat(statusOf(due2)).isEqualTo(UrlStatusEnum.EXPIRED);
        assertThat(statusOf(future)).isEqualTo(UrlStatusEnum.ACTIVE);
        assertThat(statusOf(forever)).isEqualTo(UrlStatusEnum.ACTIVE);
        assertThat(statusOf(disabled)).isEqualTo(UrlStatusEnum.DISABLED);
        assertThat(statusOf(blocked)).isEqualTo(UrlStatusEnum.BLOCKED);
        assertThat(statusOf(deleted)).isEqualTo(UrlStatusEnum.DELETED);
    }

    @Test
    void secondRunFindsNothingToDo() {
        link(UrlStatusEnum.ACTIVE, LocalDateTime.now().minusMinutes(5));

        assertThat(job.expireDueLinks()).isEqualTo(1);
        assertThat(job.expireDueLinks()).isZero();
    }

    @Test
    void auditColumnsRecordTheSystemAsUpdater() {
        Url due = link(UrlStatusEnum.ACTIVE, LocalDateTime.now().minusMinutes(5));
        LocalDateTime before = urlRepository.findById(due.getId()).orElseThrow().getUpdatedAt();

        job.expireDueLinks();

        Url reloaded = urlRepository.findById(due.getId()).orElseThrow();
        assertThat(reloaded.getUpdatedBy()).isEqualTo("system");
        assertThat(reloaded.getUpdatedAt()).isAfterOrEqualTo(before);
    }

    @Test
    void cacheEntryIsEvictedAndRedirectReportsGone() {
        Url due = link(UrlStatusEnum.ACTIVE, LocalDateTime.now().minusMinutes(5));
        urlCacheService.put(due.getShortCode(), due);
        String key = "URL:" + due.getShortCode();
        assertThat(redisTemplate.hasKey(key)).isTrue();

        job.expireDueLinks();

        assertThat(redisTemplate.hasKey(key)).isFalse();
        assertThatThrownBy(() -> redirectService.resolveTarget(due.getShortCode()))
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.URL_GONE));
    }

    @Test
    void batchSizeLimitsEachTransaction() {
        link(UrlStatusEnum.ACTIVE, LocalDateTime.now().minusMinutes(3));
        link(UrlStatusEnum.ACTIVE, LocalDateTime.now().minusMinutes(2));
        link(UrlStatusEnum.ACTIVE, LocalDateTime.now().minusMinutes(1));

        assertThat(expirationService.expireBatch(LocalDateTime.now(), 2)).hasSize(2);
        assertThat(expirationService.expireBatch(LocalDateTime.now(), 2)).hasSize(1);
        assertThat(expirationService.expireBatch(LocalDateTime.now(), 2)).isEmpty();
    }

    @Test
    void oldestExpiryIsProcessedFirst() {
        Url oldest = link(UrlStatusEnum.ACTIVE, LocalDateTime.now().minusDays(3));
        link(UrlStatusEnum.ACTIVE, LocalDateTime.now().minusDays(2));
        link(UrlStatusEnum.ACTIVE, LocalDateTime.now().minusDays(1));

        List<String> first = expirationService.expireBatch(LocalDateTime.now(), 1);

        assertThat(first).containsExactly(oldest.getShortCode());
    }

    @Test
    void expiringManyLinksIssuesAConstantNumberOfStatements() {
        int total = 50;
        for (int i = 0; i < total; i++) {
            link(UrlStatusEnum.ACTIVE, LocalDateTime.now().minusMinutes(10));
        }
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        try {
            List<String> expired = expirationService.expireBatch(LocalDateTime.now(), 100);

            // Số câu lệnh SQL không được tăng theo số link (không N+1, không N lần UPDATE)
            assertThat(expired).hasSize(total);
            assertThat(statistics.getPrepareStatementCount())
                    .as("SQL statements to expire %d links", total)
                    .isLessThanOrEqualTo(2);
        } finally {
            statistics.setStatisticsEnabled(false);
        }
    }

    @Test
    void concurrentWorkersNeverProcessTheSameLinkTwice() throws Exception {
        int total = 200;
        Set<String> mine = new java.util.HashSet<>();
        for (int i = 0; i < total; i++) {
            mine.add(link(UrlStatusEnum.ACTIVE, LocalDateTime.now().minusMinutes(10)).getShortCode());
        }

        int workers = 4;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<List<String>>> futures = new ArrayList<>();
        try {
            for (int w = 0; w < workers; w++) {
                Callable<List<String>> worker = () -> {
                    startGate.await();
                    List<String> processed = new ArrayList<>();
                    List<String> batch;
                    do {
                        batch = expirationService.expireBatch(LocalDateTime.now(), 25);
                        processed.addAll(batch);
                    } while (!batch.isEmpty());
                    return processed;
                };
                futures.add(pool.submit(worker));
            }
            startGate.countDown();

            List<String> all = new ArrayList<>();
            for (Future<List<String>> future : futures) {
                all.addAll(future.get());
            }

            // Không link nào bị hai worker cùng xử lý, và mọi link của test này đều được xử lý
            assertThat(all).doesNotHaveDuplicates();
            assertThat(all).containsAll(mine);
            assertThat(urlRepository.findAll().stream()
                    .filter(u -> mine.contains(u.getShortCode()))
                    .map(Url::getStatus)
                    .collect(Collectors.toSet()))
                    .containsExactly(UrlStatusEnum.EXPIRED);
        } finally {
            pool.shutdownNow();
        }
    }

    // ===========================
    //  HELPERS
    // ===========================

    private Url link(UrlStatusEnum status, LocalDateTime expiresAt) {
        String code = "e" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        return urlRepository.save(Url.builder()
                .userId(owner.getId())
                .shortCode(code)
                .originalUrl("https://example.com/" + code)
                .status(status)
                .expiresAt(expiresAt)
                .build());
    }

    private UrlStatusEnum statusOf(Url url) {
        return urlRepository.findById(url.getId()).orElseThrow().getStatus();
    }
}
