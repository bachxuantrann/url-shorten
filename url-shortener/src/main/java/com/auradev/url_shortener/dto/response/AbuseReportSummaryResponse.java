package com.auradev.url_shortener.dto.response;

import com.auradev.url_shortener.enums.AbuseReasonEnum;
import com.auradev.url_shortener.enums.UrlStatusEnum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Báo cáo lạm dụng gom theo link, cho admin: một dòng mỗi link.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AbuseReportSummaryResponse {

    private String shortCode;
    private String shortUrl;
    private String originalUrl;
    /** Trạng thái hiện tại của link (đã BLOCKED chưa...). */
    private UrlStatusEnum linkStatus;
    private long reportCount;
    private LocalDateTime lastReportedAt;
    /** Số báo cáo theo từng lý do. */
    private Map<AbuseReasonEnum, Long> reasons;
}
