package com.auradev.url_shortener.job;

import com.auradev.url_shortener.config.ClickProperties;
import com.auradev.url_shortener.service.ClickPartitionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;

/**
 * Job định kỳ tạo trước phân vùng tháng tới của {@code url_clicks} và xoá phân vùng quá hạn lưu trữ.
 *
 * <p>Nhiều instance cùng chạy vẫn an toàn nhờ khoá advisory trong {@link ClickPartitionService}.
 *
 * <ul>
 *   <li>{@code app.click.partition-job-interval} — chu kỳ (ISO-8601, mặc định {@code PT6H})</li>
 *   <li>{@code app.click.partition-job-enabled} — {@code false} để tắt job</li>
 *   <li>{@code app.click.partition-months-ahead} / {@code app.click.retention-months}</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.click.partition-job-enabled", havingValue = "true", matchIfMissing = true)
public class ClickPartitionJob {

    private final ClickPartitionService partitionService;
    private final ClickProperties       properties;
    private final Clock                 clock;

    @Scheduled(
            initialDelayString = "${app.click.partition-job-interval:PT6H}",
            fixedDelayString   = "${app.click.partition-job-interval:PT6H}")
    public void run() {
        try {
            ClickPartitionService.Result result = partitionService.maintain(
                    LocalDate.now(clock), properties.getPartitionMonthsAhead(), properties.getRetentionMonths());
            if (!result.created().isEmpty() || !result.dropped().isEmpty() || result.deletedVisitorRows() > 0) {
                log.info("Click partitions: created {}, dropped {}, expired visitor rows deleted {}",
                        result.created(), result.dropped(), result.deletedVisitorRows());
            }
        } catch (RuntimeException e) {
            log.error("Click partition job failed, will retry on next run", e);
        }
    }
}
