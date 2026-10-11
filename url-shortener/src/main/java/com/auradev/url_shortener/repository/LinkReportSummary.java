package com.auradev.url_shortener.repository;

import com.auradev.url_shortener.enums.UrlStatusEnum;

import java.time.LocalDateTime;

/**
 * Một dòng của danh sách báo cáo theo link: số báo cáo và thời điểm báo gần nhất (một dòng mỗi link).
 */
public record LinkReportSummary(
        Long urlId,
        String shortCode,
        String originalUrl,
        UrlStatusEnum linkStatus,
        long reportCount,
        LocalDateTime lastReportedAt
) {
}
