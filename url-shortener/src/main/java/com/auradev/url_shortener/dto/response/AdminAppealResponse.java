package com.auradev.url_shortener.dto.response;

import com.auradev.url_shortener.enums.AppealStatusEnum;
import com.auradev.url_shortener.enums.UrlStatusEnum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Một kháng nghị trong danh sách của admin, kèm link bị chặn, lý do chặn và chủ link.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminAppealResponse {

    private Long id;
    private String shortCode;
    private String shortUrl;
    private String originalUrl;
    /** Trạng thái hiện tại của link (BLOCKED, hoặc ACTIVE sau khi chấp nhận). */
    private UrlStatusEnum linkStatus;
    private String blockedReason;
    /** Username của chủ link. */
    private String owner;
    private String message;
    private AppealStatusEnum status;
    private String response;
    private LocalDateTime createdAt;
    /** Lần cập nhật cuối: thời điểm xử lý khi kháng nghị không còn OPEN. */
    private LocalDateTime updatedAt;
}
