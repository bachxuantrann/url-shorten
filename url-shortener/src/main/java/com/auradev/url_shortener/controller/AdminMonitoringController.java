package com.auradev.url_shortener.controller;

import com.auradev.url_shortener.constant.AppConstant;
import com.auradev.url_shortener.dto.response.ApiResponse;
import com.auradev.url_shortener.dto.response.LogEntryResponse;
import com.auradev.url_shortener.dto.response.LogSummaryResponse;
import com.auradev.url_shortener.dto.response.MonitoringStatusResponse;
import com.auradev.url_shortener.dto.response.PageResponse;
import com.auradev.url_shortener.exception.AppException;
import com.auradev.url_shortener.exception.ErrorCode;
import com.auradev.url_shortener.service.MonitoringService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Admin xem log hệ thống qua OpenObserve (backend gọi OpenObserve, trình duyệt không bao giờ thấy thông tin đăng nhập).
 *
 * <p>Endpoints (base path {@code /api/v1/admin/monitoring}, yêu cầu {@code ROLE_ADMIN}):
 * <ul>
 *   <li>GET {@code /status}  — Đã cấu hình / OpenObserve có đang trả lời, địa chỉ giao diện OpenObserve</li>
 *   <li>GET {@code /summary} — Số dòng log theo mức và top exception trong {@code minutes} phút gần nhất</li>
 *   <li>GET {@code /logs}    — Các dòng log mới nhất trước (lọc mức, từ khoá, khoảng thời gian, phân trang)</li>
 * </ul>
 */
@RestController
@RequestMapping(AppConstant.API_ADMIN + "/monitoring")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminMonitoringController {

    private static final int MAX_PAGE_SIZE = 100;

    private final MonitoringService monitoringService;

    @GetMapping("/status")
    public ResponseEntity<ApiResponse<MonitoringStatusResponse>> status() {
        return ResponseEntity.ok(ApiResponse.success(null, monitoringService.status()));
    }

    /** @param minutes {@code 15 | 60 | 360 | 1440} (mặc định 60) */
    @GetMapping("/summary")
    public ResponseEntity<ApiResponse<LogSummaryResponse>> summary(@RequestParam(defaultValue = "60") int minutes) {
        return ResponseEntity.ok(ApiResponse.success(null, monitoringService.summary(minutes)));
    }

    /**
     * @param levels  danh sách mức cách nhau bằng dấu phẩy, ví dụ {@code ERROR,WARN} (mặc định ERROR,WARN)
     * @param keyword chứa chuỗi này, không phân biệt hoa thường, tối đa 100 ký tự
     * @param minutes {@code 15 | 60 | 360 | 1440} (mặc định 60)
     */
    @GetMapping("/logs")
    public ResponseEntity<ApiResponse<PageResponse<LogEntryResponse>>> logs(
            @RequestParam(defaultValue = "ERROR,WARN") String levels,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "60") int minutes,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size
    ) {
        if (keyword != null && keyword.length() > 200) {
            throw new AppException(ErrorCode.VALIDATION_FAILED);
        }
        Set<String> parsed = new LinkedHashSet<>(Arrays.stream(levels.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList());
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE));
        return ResponseEntity.ok(ApiResponse.success(null, monitoringService.logs(parsed, keyword, minutes, pageable)));
    }
}
