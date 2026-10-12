package com.auradev.url_shortener.dto.response;

import com.auradev.url_shortener.enums.AppealStatusEnum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Kháng nghị hiện tại của một link bị chặn, chủ link thấy trong {@link UrlResponse#getAppeal()}. Chỉ có khi link
 * đang bị chặn và kháng nghị còn liên quan đến lần chặn này (đang chờ, hoặc đã bị từ chối).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AppealInfoResponse {

    /** {@code OPEN} (chờ xem xét) hoặc {@code REJECTED}. */
    private AppealStatusEnum status;
    private String message;
    /** Lời phản hồi của admin khi từ chối (có thể trống). */
    private String response;
    private LocalDateTime createdAt;
}
