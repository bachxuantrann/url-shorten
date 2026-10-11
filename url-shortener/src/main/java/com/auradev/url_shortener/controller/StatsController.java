package com.auradev.url_shortener.controller;

import com.auradev.url_shortener.constant.AppConstant;
import com.auradev.url_shortener.dto.response.ApiResponse;
import com.auradev.url_shortener.dto.response.StatsOverviewResponse;
import com.auradev.url_shortener.service.UrlStatsService;
import com.auradev.url_shortener.utils.SecurityUtils;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * Thống kê tổng quan của người dùng đã đăng nhập, base path {@code /api/v1/stats} (yêu cầu xác thực).
 *
 * <ul>
 *   <li>GET {@code /overview} — Số link theo trạng thái, click theo ngày, link nhiều click nhất và phân rã của
 *       <b>mọi link</b> của tôi</li>
 * </ul>
 *
 * <p>Đặt ở base path riêng (không phải {@code /api/v1/urls/...}) để không đụng với short code do người dùng tự đặt.
 */
@RestController
@RequestMapping(AppConstant.API_STATS)
@RequiredArgsConstructor
public class StatsController {

    private final UrlStatsService urlStatsService;

    /**
     * {@code from}/{@code to} dạng {@code yyyy-MM-dd}, cả hai đầu đều tính; mặc định 30 ngày gần nhất, tối đa
     * {@value UrlStatsService#MAX_DAYS} ngày (vượt thì {@code INVALID_STATS_RANGE}).
     */
    @GetMapping("/overview")
    public ResponseEntity<ApiResponse<StatsOverviewResponse>> overview(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            HttpServletRequest request
    ) {
        return ResponseEntity.ok(ApiResponse.success(null,
                urlStatsService.getOverview(SecurityUtils.getAuthenticatedUserId(request), from, to)));
    }
}
