package com.auradev.url_shortener.controller;

import com.auradev.url_shortener.constant.AppConstant;
import com.auradev.url_shortener.dto.request.MoveReportsRequest;
import com.auradev.url_shortener.dto.response.AbuseReportDetailResponse;
import com.auradev.url_shortener.dto.response.AbuseReportSummaryResponse;
import com.auradev.url_shortener.dto.response.ApiResponse;
import com.auradev.url_shortener.dto.response.PageResponse;
import com.auradev.url_shortener.enums.AbuseReportStatusEnum;
import com.auradev.url_shortener.service.AbuseReportService;
import com.auradev.url_shortener.utils.TranslatorUtils;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Admin xem xét các báo cáo lạm dụng, gom theo link.
 *
 * <p>Endpoints (base path {@code /api/v1/admin/reports}, yêu cầu {@code ROLE_ADMIN}):
 * <ul>
 *   <li>GET {@code /}                      — Mỗi link có báo cáo là một dòng (lọc theo status, mặc định OPEN)</li>
 *   <li>GET {@code /{shortCode}}           — Các báo cáo cụ thể của một link</li>
 *   <li>PUT {@code /{shortCode}/resolve}   — Đánh dấu đã xử lý (trả về số báo cáo đã đóng)</li>
 *   <li>PUT {@code /{shortCode}/dismiss}   — Đánh dấu không phải lạm dụng</li>
 *   <li>PUT {@code /{shortCode}/move}      — Chuyển báo cáo của link giữa các trạng thái, mọi chiều (body {@code {"from","to"}})</li>
 * </ul>
 *
 * <p>Chặn link dùng endpoint sẵn có {@code PUT /api/v1/admin/urls/{code}/block}; việc chặn tự đóng các báo cáo
 * đang mở của link đó là đã xử lý.
 */
@RestController
@RequestMapping(AppConstant.API_ADMIN + "/reports")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminReportController {

    private final AbuseReportService abuseReportService;
    private final TranslatorUtils    translator;

    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<AbuseReportSummaryResponse>>> list(
            @RequestParam(defaultValue = "OPEN") AbuseReportStatusEnum status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + AppConstant.DEFAULT_PAGE_SIZE) int size
    ) {
        PageRequest pageable = PageRequest.of(
                Math.max(page, 0),
                Math.min(Math.max(size, 1), AppConstant.MAX_PAGE_SIZE));
        return ResponseEntity.ok(ApiResponse.success(null, abuseReportService.list(status, pageable)));
    }

    @GetMapping("/{shortCode}")
    public ResponseEntity<ApiResponse<List<AbuseReportDetailResponse>>> details(
            @PathVariable String shortCode,
            @RequestParam(defaultValue = "OPEN") AbuseReportStatusEnum status
    ) {
        return ResponseEntity.ok(ApiResponse.success(null, abuseReportService.details(shortCode, status)));
    }

    @PutMapping("/{shortCode}/resolve")
    public ResponseEntity<ApiResponse<Integer>> resolve(@PathVariable String shortCode) {
        int closed = abuseReportService.close(shortCode, AbuseReportStatusEnum.RESOLVED);
        return ResponseEntity.ok(ApiResponse.success(translator.toLocale("api.success.reports_resolved"), closed));
    }

    @PutMapping("/{shortCode}/move")
    public ResponseEntity<ApiResponse<Integer>> move(
            @PathVariable String shortCode,
            @Valid @RequestBody MoveReportsRequest body
    ) {
        int moved = abuseReportService.move(shortCode, body.getFrom(), body.getTo());
        return ResponseEntity.ok(ApiResponse.success(translator.toLocale("api.success.reports_moved"), moved));
    }

    @PutMapping("/{shortCode}/dismiss")
    public ResponseEntity<ApiResponse<Integer>> dismiss(@PathVariable String shortCode) {
        int closed = abuseReportService.close(shortCode, AbuseReportStatusEnum.DISMISSED);
        return ResponseEntity.ok(ApiResponse.success(translator.toLocale("api.success.reports_dismissed"), closed));
    }
}
