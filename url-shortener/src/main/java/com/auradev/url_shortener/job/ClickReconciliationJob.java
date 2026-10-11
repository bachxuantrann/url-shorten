package com.auradev.url_shortener.job;

import com.auradev.url_shortener.config.ClickProperties;
import com.auradev.url_shortener.service.ClickReconciliationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;

/**
 * Job định kỳ đối soát bộ đếm click (xem {@link ClickReconciliationService}). Mặc định chỉ phát hiện và
 * ghi log; bật {@code app.click.reconcile-repair=true} để tự sửa {@code urls.click_count}.
 *
 * <p>Mọi instance đều chạy được: phát hiện là chỉ đọc, còn sửa là cập nhật idempotent dưới khoá dòng.
 *
 * <ul>
 *   <li>{@code app.click.reconcile-job-interval} — chu kỳ (ISO-8601, mặc định {@code PT24H})</li>
 *   <li>{@code app.click.reconcile-job-enabled} — {@code false} để tắt job</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.click.reconcile-job-enabled", havingValue = "true", matchIfMissing = true)
public class ClickReconciliationJob {

    private final ClickReconciliationService reconciliationService;
    private final ClickProperties            properties;
    private final Clock                      clock;

    @Scheduled(
            initialDelayString = "${app.click.reconcile-job-interval:PT24H}",
            fixedDelayString   = "${app.click.reconcile-job-interval:PT24H}")
    public void run() {
        try {
            ClickReconciliationService.Result result = reconciliationService.reconcile(
                    LocalDate.now(clock),
                    properties.getReconcileWindowDays(),
                    properties.getReconcileBatchSize(),
                    properties.isReconcileRepair());
            log.info("Click reconciliation: {} counter mismatch(es){}, {} repaired, {} day mismatch(es)",
                    result.counterMismatches().size(), result.truncated() ? "+" : "",
                    result.repaired(), result.dayMismatches());
        } catch (RuntimeException e) {
            log.error("Click reconciliation failed, will retry on next run", e);
        }
    }
}
