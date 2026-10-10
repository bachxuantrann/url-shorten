package com.auradev.url_shortener.controller;

import com.auradev.url_shortener.constant.AppConstant;
import com.auradev.url_shortener.dto.request.CreateUrlRequest;
import com.auradev.url_shortener.dto.request.UpdateUrlRequest;
import com.auradev.url_shortener.dto.response.ApiResponse;
import com.auradev.url_shortener.dto.response.PageResponse;
import com.auradev.url_shortener.dto.response.UrlResponse;
import com.auradev.url_shortener.enums.UrlStatusEnum;
import com.auradev.url_shortener.exception.AppException;
import com.auradev.url_shortener.exception.ErrorCode;
import com.auradev.url_shortener.service.UrlService;
import com.auradev.url_shortener.utils.SecurityUtils;
import com.auradev.url_shortener.utils.TranslatorUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Set;
import java.util.UUID;

/**
 * Controller quản lý short link của người dùng đã đăng nhập.
 *
 * <p>Endpoints (base path {@code /api/v1/urls}, yêu cầu xác thực):
 * <ul>
 *   <li>POST   {@code /}                 — Tạo link</li>
 *   <li>GET    {@code /}                 — Danh sách link của tôi (phân trang, tìm keyword, lọc status)</li>
 *   <li>GET    {@code /{shortCode}}      — Xem một link</li>
 *   <li>PUT    {@code /{shortCode}}      — Cập nhật URL đích / hạn</li>
 *   <li>PUT    {@code /{shortCode}/disable} — Tắt link</li>
 *   <li>PUT    {@code /{shortCode}/enable}  — Bật lại link</li>
 *   <li>DELETE {@code /{shortCode}}      — Xoá (mềm)</li>
 * </ul>
 */
@RestController
@RequestMapping(AppConstant.API_URLS)
@RequiredArgsConstructor
public class UrlController {

    /** Các field cho phép sắp xếp — không nhận tên field tuỳ ý từ client */
    private static final Set<String> SORTABLE_FIELDS = Set.of("createdAt", "clickCount", "expiresAt");

    private static final int MAX_KEYWORD_LENGTH = 100;

    private final UrlService      urlService;
    private final TranslatorUtils translator;

    @PostMapping
    public ResponseEntity<ApiResponse<UrlResponse>> create(
            @Valid @RequestBody CreateUrlRequest body,
            HttpServletRequest request
    ) {
        UUID userId = SecurityUtils.getAuthenticatedUserId(request);
        UrlResponse created = urlService.create(userId, body);
        String message = translator.toLocale("api.success.url_created");
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(message, created));
    }

    /**
     * @param keyword tìm theo URL đích hoặc short code — khớp một phần, không phân biệt hoa thường,
     *                tối đa {@value #MAX_KEYWORD_LENGTH} ký tự (optional)
     * @param status  lọc theo trạng thái (optional)
     * @param page    số trang, 0-indexed (default: 0)
     * @param size    số phần tử mỗi trang (default: 20, max: 100)
     * @param sortBy  {@code createdAt} | {@code clickCount} | {@code expiresAt} (default: createdAt)
     * @param sortDir {@code asc} | {@code desc} (default: desc)
     */
    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<UrlResponse>>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) UrlStatusEnum status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + AppConstant.DEFAULT_PAGE_SIZE) int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir,
            HttpServletRequest request
    ) {
        if (!SORTABLE_FIELDS.contains(sortBy)
                || (keyword != null && keyword.length() > MAX_KEYWORD_LENGTH)) {
            throw new AppException(ErrorCode.VALIDATION_FAILED);
        }
        UUID userId = SecurityUtils.getAuthenticatedUserId(request);

        Sort sort = sortDir.equalsIgnoreCase("asc") ? Sort.by(sortBy).ascending() : Sort.by(sortBy).descending();
        Pageable pageable = PageRequest.of(
                Math.max(page, 0),
                Math.min(Math.max(size, 1), AppConstant.MAX_PAGE_SIZE),
                sort);

        return ResponseEntity.ok(ApiResponse.success(null, urlService.list(userId, keyword, status, pageable)));
    }

    @GetMapping("/{shortCode}")
    public ResponseEntity<ApiResponse<UrlResponse>> get(
            @PathVariable String shortCode,
            HttpServletRequest request
    ) {
        UUID userId = SecurityUtils.getAuthenticatedUserId(request);
        return ResponseEntity.ok(ApiResponse.success(null, urlService.get(userId, shortCode)));
    }

    @PutMapping("/{shortCode}")
    public ResponseEntity<ApiResponse<UrlResponse>> update(
            @PathVariable String shortCode,
            @Valid @RequestBody UpdateUrlRequest body,
            HttpServletRequest request
    ) {
        UUID userId = SecurityUtils.getAuthenticatedUserId(request);
        UrlResponse updated = urlService.update(userId, shortCode, body);
        String message = translator.toLocale("api.success.url_updated");
        return ResponseEntity.ok(ApiResponse.success(message, updated));
    }

    @PutMapping("/{shortCode}/disable")
    public ResponseEntity<ApiResponse<UrlResponse>> disable(
            @PathVariable String shortCode,
            HttpServletRequest request
    ) {
        UUID userId = SecurityUtils.getAuthenticatedUserId(request);
        UrlResponse disabled = urlService.disable(userId, shortCode);
        String message = translator.toLocale("api.success.url_disabled");
        return ResponseEntity.ok(ApiResponse.success(message, disabled));
    }

    @PutMapping("/{shortCode}/enable")
    public ResponseEntity<ApiResponse<UrlResponse>> enable(
            @PathVariable String shortCode,
            HttpServletRequest request
    ) {
        UUID userId = SecurityUtils.getAuthenticatedUserId(request);
        UrlResponse enabled = urlService.enable(userId, shortCode);
        String message = translator.toLocale("api.success.url_enabled");
        return ResponseEntity.ok(ApiResponse.success(message, enabled));
    }

    @DeleteMapping("/{shortCode}")
    public ResponseEntity<ApiResponse<Void>> delete(
            @PathVariable String shortCode,
            HttpServletRequest request
    ) {
        UUID userId = SecurityUtils.getAuthenticatedUserId(request);
        urlService.delete(userId, shortCode);
        String message = translator.toLocale("api.success.url_deleted");
        return ResponseEntity.ok(ApiResponse.success(message, null));
    }
}
