package com.auradev.url_shortener.dto.request;

import com.auradev.url_shortener.enums.AbuseReasonEnum;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * Request DTO để một người (không cần đăng nhập) báo cáo lạm dụng một link.
 */
@Getter
@Setter
public class ReportAbuseRequest {

    /** Mã của link bị báo cáo (phần sau dấu {@code /} của link rút gọn). */
    @NotBlank(message = "{validation.report.code.required}")
    @Size(max = 32, message = "{validation.report.code.size}")
    private String shortCode;

    @NotNull(message = "{validation.report.reason.required}")
    private AbuseReasonEnum reason;

    /** Mô tả thêm của người báo, tuỳ chọn. */
    @Size(max = 500, message = "{validation.report.details.size}")
    private String details;
}
