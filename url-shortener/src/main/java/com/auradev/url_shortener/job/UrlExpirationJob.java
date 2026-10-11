package com.auradev.url_shortener.job;

import com.auradev.url_shortener.config.ShortenerProperties;
import com.auradev.url_shortener.service.UrlExpirationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Job định kỳ chuyển link ACTIVE đã quá hạn sang EXPIRED.
 *
 * <p>Job chỉ là bước dọn dẹp/đồng bộ trạng thái: tính đúng đắn của redirect không phụ thuộc vào nó
 * vì {@code RedirectServiceImpl} luôn so {@code expiresAt} với giờ hiện tại (kiểm tra lười).
 *
 * <p><b>Nhiều instance:</b> mọi instance đều chạy job, nhưng truy vấn dùng
 * {@code FOR UPDATE SKIP LOCKED} nên mỗi link chỉ được một instance xử lý, không cần khoá phân tán.
 *
 * <p>Cấu hình:
 * <ul>
 *   <li>{@code app.shortener.expiry-job-interval} — chu kỳ (ISO-8601, mặc định {@code PT1M})</li>
 *   <li>{@code app.shortener.expiry-job-enabled} — {@code false} để tắt job (mặc định bật)</li>
 *   <li>{@code app.shortener.expiry-batch-size} — số link mỗi batch (mặc định 500)</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.shortener.expiry-job-enabled", havingValue = "true", matchIfMissing = true)
public class UrlExpirationJob {

    /** Chặn một lần chạy kéo dài vô hạn nếu có quá nhiều link quá hạn (lần chạy sau làm tiếp). */
    private static final int MAX_BATCHES_PER_RUN = 100;

    private final UrlExpirationService expirationService;
    private final ShortenerProperties  properties;
    private final Clock                clock;

    @Scheduled(
            initialDelayString = "${app.shortener.expiry-job-interval:PT1M}",
            fixedDelayString   = "${app.shortener.expiry-job-interval:PT1M}")
    public void run() {
        try {
            int expired = expireDueLinks();
            if (expired > 0) {
                log.info("Expiry job: moved {} link(s) to EXPIRED", expired);
            }
        } catch (RuntimeException e) {
            // Không để lỗi thoáng qua (DB/Redis) làm dừng các lần chạy sau
            log.error("Expiry job failed, will retry on next run", e);
        }
    }

    /**
     * Xử lý hết các link quá hạn (theo từng batch, mỗi batch một transaction).
     *
     * @return tổng số link đã chuyển sang EXPIRED
     */
    public int expireDueLinks() {
        int batchSize = Math.max(1, properties.getExpiryBatchSize());
        int total = 0;

        for (int i = 0; i < MAX_BATCHES_PER_RUN; i++) {
            List<String> expired = expirationService.expireBatch(LocalDateTime.now(clock), batchSize);
            total += expired.size();
            if (expired.size() < batchSize) {
                break;
            }
        }
        return total;
    }
}
