package com.auradev.url_shortener.dto.response;

import com.auradev.url_shortener.enums.UrlStatusEnum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Response DTO trả về thông tin một short link.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UrlResponse {

    private String shortCode;
    /** Link rút gọn đầy đủ, ví dụ {@code https://sho.rt/aB3xK9z} */
    private String shortUrl;
    private String originalUrl;
    private UrlStatusEnum status;
    /** Link có đặt mật khẩu hay không (không bao giờ trả về mật khẩu hay hash) */
    private boolean passwordProtected;
    /** Lý do bị chặn (chỉ có khi status = BLOCKED) */
    private String blockedReason;
    private LocalDateTime expiresAt;
    private long clickCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
