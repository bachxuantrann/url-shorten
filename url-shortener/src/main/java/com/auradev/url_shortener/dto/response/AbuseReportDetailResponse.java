package com.auradev.url_shortener.dto.response;

import com.auradev.url_shortener.enums.AbuseReasonEnum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Một báo cáo lạm dụng cụ thể (admin xem). Không có thông tin nhận dạng người báo.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AbuseReportDetailResponse {

    private Long id;
    private AbuseReasonEnum reason;
    private String details;
    private LocalDateTime createdAt;
}
