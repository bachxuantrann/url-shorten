package com.auradev.url_shortener.dto.response;

import com.auradev.url_shortener.enums.UrlStatusEnum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Thông tin link dành cho admin: gồm chủ sở hữu ({@code userId}, null nếu là link ẩn danh) và người tạo/sửa.
 * Không dùng cho API của người dùng thường.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminUrlResponse {

    private String shortCode;
    private String shortUrl;
    private String originalUrl;
    private UrlStatusEnum status;
    private String blockedReason;
    private UUID userId;
    private LocalDateTime expiresAt;
    private long clickCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private String createdBy;
    private String updatedBy;
}
