package com.auradev.url_shortener.repository;

import com.auradev.url_shortener.enums.AppealStatusEnum;
import com.auradev.url_shortener.enums.UrlStatusEnum;

import java.time.LocalDateTime;

/**
 * Một dòng của danh sách kháng nghị cho admin: kháng nghị cùng link và chủ link, lấy bằng một câu truy vấn.
 */
public record AppealRow(
        Long id,
        String message,
        AppealStatusEnum status,
        String response,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        String shortCode,
        String originalUrl,
        UrlStatusEnum linkStatus,
        String blockedReason,
        String ownerUsername
) {
}
