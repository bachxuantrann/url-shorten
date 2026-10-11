package com.auradev.url_shortener.controller;

import com.auradev.url_shortener.constant.AppConstant;
import com.auradev.url_shortener.dto.request.BlockUrlRequest;
import com.auradev.url_shortener.dto.response.AdminUrlResponse;
import com.auradev.url_shortener.dto.response.ApiResponse;
import com.auradev.url_shortener.dto.response.PageResponse;
import com.auradev.url_shortener.dto.response.UrlStatsResponse;
import com.auradev.url_shortener.enums.UrlStatusEnum;
import com.auradev.url_shortener.exception.AppException;
import com.auradev.url_shortener.exception.ErrorCode;
import com.auradev.url_shortener.service.AdminUrlService;
import com.auradev.url_shortener.service.UrlStatsService;
import com.auradev.url_shortener.utils.TranslatorUtils;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Set;

/**
 * Admin xem và chặn/gỡ chặn link của bất kỳ ai.
 *
 * <p>Endpoints (base path {@code /api/v1/admin/urls}, yêu cầu {@code ROLE_ADMIN}):
 * <ul>
 *   <li>GET {@code /}                    — Danh sách link của mọi người (phân trang, tìm keyword, lọc status)</li>
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

    /** Các field cho phép sắp xếp: không nhận tên field tuỳ ý từ client */
    private static final Set<String> SORTABLE_FIELDS = Set.of("createdAt", "clickCount", "expiresAt");

    private static final int MAX_KEYWORD_LENGTH = 100;

    private final AdminUrlService adminUrlService;
    private final UrlStatsService urlStatsService;
    private final TranslatorUtils translator;

    /**
     * Danh sách link của mọi người dùng.
     *
     * @param keyword khớp một phần, không phân biệt hoa thường, trên URL đích, short code hoặc người tạo
     *                (username, hoặc {@code anonymous}); tối đa {@value #MAX_KEYWORD_LENGTH} ký tự
     * @param status  lọc theo trạng thái (optional; {@code DELETED} không bao giờ trả về)
     * @param sortBy  {@code createdAt} | {@code clickCount} | {@code expiresAt} (default: createdAt)
     */
    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<AdminUrlResponse>>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) UrlStatusEnum status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + AppConstant.DEFAULT_PAGE_SIZE) int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir
    ) {
        if (!SORTABLE_FIELDS.contains(sortBy)
                || (keyword != null && keyword.length() > MAX_KEYWORD_LENGTH)) {
            throw new AppException(ErrorCode.VALIDATION_FAILED);
        }
        Sort sort = sortDir.equalsIgnoreCase("asc") ? Sort.by(sortBy).ascending() : Sort.by(sortBy).descending();
        // Thêm id làm khoá phụ để phân trang ổn định khi nhiều link cùng giá trị (clickCount, expiresAt)
        sort = sort.and(Sort.by("id").descending());
        Pageable pageable = PageRequest.of(
                Math.max(page, 0),
                Math.min(Math.max(size, 1), AppConstant.MAX_PAGE_SIZE),
                sort);
        return ResponseEntity.ok(ApiResponse.success(null, adminUrlService.list(keyword, status, pageable)));
    }

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
