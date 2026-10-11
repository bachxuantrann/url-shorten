package com.auradev.url_shortener.controller;

import com.auradev.url_shortener.constant.AppConstant;
import com.auradev.url_shortener.dto.request.BlockUrlRequest;
import com.auradev.url_shortener.dto.response.AdminUrlResponse;
import com.auradev.url_shortener.dto.response.ApiResponse;
import com.auradev.url_shortener.dto.response.UrlStatsResponse;
import com.auradev.url_shortener.service.AdminUrlService;
import com.auradev.url_shortener.service.UrlStatsService;
import com.auradev.url_shortener.utils.TranslatorUtils;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

/**
 * Admin xem và chặn/gỡ chặn link của bất kỳ ai.
 *
 * <p>Endpoints (base path {@code /api/v1/admin/urls}, yêu cầu {@code ROLE_ADMIN}):
 * <ul>
 *   <li>GET {@code /{shortCode}}         — Xem link (kèm chủ sở hữu)</li>
 *   <li>GET {@code /{shortCode}/stats}   — Thống kê click của link</li>
 *   <li>PUT {@code /{shortCode}/block}   — Chặn link (bắt buộc có lý do)</li>
 *   <li>PUT {@code /{shortCode}/unblock} — Gỡ chặn</li>
 * </ul>
 */
@RestController
@RequestMapping(AppConstant.API_ADMIN + "/urls")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminUrlController {

    private final AdminUrlService adminUrlService;
    private final UrlStatsService urlStatsService;
    private final TranslatorUtils translator;

    @GetMapping("/{shortCode}")
    public ResponseEntity<ApiResponse<AdminUrlResponse>> get(@PathVariable String shortCode) {
        return ResponseEntity.ok(ApiResponse.success(null, adminUrlService.getUrl(shortCode)));
    }

    /** Thống kê click của bất kỳ link nào (kể cả link ẩn danh). Cùng quy tắc khoảng ngày như API của chủ link. */
    @GetMapping("/{shortCode}/stats")
    public ResponseEntity<ApiResponse<UrlStatsResponse>> stats(
            @PathVariable String shortCode,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        return ResponseEntity.ok(ApiResponse.success(null, urlStatsService.getAdminStats(shortCode, from, to)));
    }

    @PutMapping("/{shortCode}/block")
    public ResponseEntity<ApiResponse<AdminUrlResponse>> block(
            @PathVariable String shortCode,
            @Valid @RequestBody BlockUrlRequest body
    ) {
        AdminUrlResponse blocked = adminUrlService.blockUrl(shortCode, body.getReason());
        return ResponseEntity.ok(ApiResponse.success(translator.toLocale("api.success.url_blocked"), blocked));
    }

    @PutMapping("/{shortCode}/unblock")
    public ResponseEntity<ApiResponse<AdminUrlResponse>> unblock(@PathVariable String shortCode) {
        AdminUrlResponse unblocked = adminUrlService.unblockUrl(shortCode);
        return ResponseEntity.ok(ApiResponse.success(translator.toLocale("api.success.url_unblocked"), unblocked));
    }
}
