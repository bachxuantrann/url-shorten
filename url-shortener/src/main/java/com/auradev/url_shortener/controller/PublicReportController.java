package com.auradev.url_shortener.controller;

import com.auradev.url_shortener.config.RateLimitProperties;
import com.auradev.url_shortener.constant.AppConstant;
import com.auradev.url_shortener.dto.request.ReportAbuseRequest;
import com.auradev.url_shortener.dto.response.ApiResponse;
import com.auradev.url_shortener.exception.AppException;
import com.auradev.url_shortener.exception.ErrorCode;
import com.auradev.url_shortener.service.AbuseReportService;
import com.auradev.url_shortener.utils.ClientIpUtils;
import com.auradev.url_shortener.utils.RateLimitService;
import com.auradev.url_shortener.utils.ShortCodes;
import com.auradev.url_shortener.utils.TranslatorUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Báo cáo lạm dụng link, công khai (không cần đăng nhập), base path {@code /api/v1/public/reports}.
 *
 * <ul>
 *   <li>POST {@code /} — Báo một link là spam/lừa đảo/mã độc... để admin xem xét</li>
 * </ul>
 *
 * <p>Giới hạn tốc độ theo IP (mọi request đều tính, kể cả bị từ chối) để không bị dùng làm công cụ spam admin.
 * Trả {@code 201} cả khi người này đã báo link đó trước đây hoặc link đã bị chặn, để không lộ trạng thái nội bộ.
 */
@RestController
@RequestMapping(AppConstant.API_PUBLIC + "/reports")
@RequiredArgsConstructor
public class PublicReportController {

    private final AbuseReportService  abuseReportService;
    private final TranslatorUtils     translator;
    private final RateLimitService    rateLimitService;
    private final RateLimitProperties rateLimitProperties;

    @PostMapping
    public ResponseEntity<ApiResponse<Void>> report(
            @Valid @RequestBody ReportAbuseRequest body,
            HttpServletRequest request
    ) {
        String ip = ClientIpUtils.resolve(request);
        rateLimitService.check("report-ip", ip, rateLimitProperties.getReportPerIp());

        // Mã không thể tồn tại thì từ chối luôn, không chạm DB
        if (!ShortCodes.isResolvable(body.getShortCode())) {
            throw new AppException(ErrorCode.URL_NOT_FOUND, body.getShortCode());
        }
        abuseReportService.submit(body.getShortCode(), body.getReason(), body.getDetails(), ip);

        String message = translator.toLocale("api.success.report_received");
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(message, null));
    }
}
