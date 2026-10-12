package com.auradev.url_shortener.dto.request;

import com.auradev.url_shortener.enums.AbuseReportStatusEnum;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/**
 * Request DTO để admin chuyển các báo cáo của một link từ trạng thái {@code from} sang {@code to} (kéo thẻ giữa các tab
 * Chờ xử lý / Đã xử lý / Đã bỏ qua, theo cả hai chiều).
 */
@Getter
@Setter
public class MoveReportsRequest {

    @NotNull(message = "{validation.report.status.required}")
    private AbuseReportStatusEnum from;

    @NotNull(message = "{validation.report.status.required}")
    private AbuseReportStatusEnum to;
}
