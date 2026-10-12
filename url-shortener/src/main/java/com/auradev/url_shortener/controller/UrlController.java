package com.auradev.url_shortener.controller;

import com.auradev.url_shortener.config.RateLimitProperties;
import com.auradev.url_shortener.constant.AppConstant;
import com.auradev.url_shortener.dto.request.AppealRequest;
import com.auradev.url_shortener.dto.request.CreateUrlRequest;
import com.auradev.url_shortener.dto.request.SetLinkPasswordRequest;
import com.auradev.url_shortener.dto.request.UpdateUrlRequest;
import com.auradev.url_shortener.dto.response.ApiResponse;
import com.auradev.url_shortener.dto.response.PageResponse;
import com.auradev.url_shortener.dto.response.UrlResponse;
import com.auradev.url_shortener.enums.UrlStatusEnum;
import com.auradev.url_shortener.exception.AppException;
import com.auradev.url_shortener.exception.ErrorCode;
import com.auradev.url_shortener.dto.response.UrlStatsResponse;
import com.auradev.url_shortener.service.LinkAppealService;
import com.auradev.url_shortener.service.UrlService;
import com.auradev.url_shortener.service.UrlStatsService;
import com.auradev.url_shortener.utils.QrCodeGenerator;
import com.auradev.url_shortener.utils.RateLimitService;
import com.auradev.url_shortener.utils.SecurityUtils;
import com.auradev.url_shortener.utils.TranslatorUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import org.springframework.format.annotation.DateTimeFormat;

import java.time.Duration;
import java.time.LocalDate;
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
 *   <li>PUT    {@code /{shortCode}/password} — Đặt hoặc đổi mật khẩu bảo vệ link</li>
 *   <li>DELETE {@code /{shortCode}/password} — Gỡ mật khẩu</li>
 *   <li>POST   {@code /{shortCode}/appeal} — Kháng nghị link bị chặn (body {@code {"message": "..."}})</li>
 *   <li>GET    {@code /{shortCode}/qr}   — Mã QR (PNG) của short URL, tham số {@code size} 128-1024</li>
 *   <li>GET    {@code /{shortCode}/stats} — Thống kê click theo ngày/quốc gia/thiết bị, tham số {@code from}/{@code to}</li>
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

    private final UrlService          urlService;
    private final UrlStatsService     urlStatsService;
    private final LinkAppealService   linkAppealService;
    private final TranslatorUtils     translator;
    private final RateLimitService    rateLimitService;
    private final RateLimitProperties rateLimitProperties;

    @PostMapping
    public ResponseEntity<ApiResponse<UrlResponse>> create(
            @Valid @RequestBody CreateUrlRequest body,
            HttpServletRequest request
    ) {
        UUID userId = SecurityUtils.getAuthenticatedUserId(request);
        rateLimitService.check("user-create", userId.toString(), rateLimitProperties.getUserCreate());
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

    /**
     * Đặt hoặc đổi mật khẩu. BCrypt tốn CPU nên dùng chung ngân sách {@code user-create} theo user để
     * một tài khoản không thể dùng endpoint này làm bơm CPU.
     */
    @PutMapping("/{shortCode}/password")
    public ResponseEntity<ApiResponse<UrlResponse>> setPassword(
            @PathVariable String shortCode,
            @Valid @RequestBody SetLinkPasswordRequest body,
            HttpServletRequest request
    ) {
        UUID userId = SecurityUtils.getAuthenticatedUserId(request);
        rateLimitService.check("user-password", userId.toString(), rateLimitProperties.getUserCreate());
        UrlResponse updated = urlService.setPassword(userId, shortCode, body.getPassword());
        String message = translator.toLocale("api.success.url_password_set");
        return ResponseEntity.ok(ApiResponse.success(message, updated));
    }

    @DeleteMapping("/{shortCode}/password")
    public ResponseEntity<ApiResponse<UrlResponse>> removePassword(
            @PathVariable String shortCode,
            HttpServletRequest request
    ) {
        UUID userId = SecurityUtils.getAuthenticatedUserId(request);
        UrlResponse updated = urlService.removePassword(userId, shortCode);
        String message = translator.toLocale("api.success.url_password_removed");
        return ResponseEntity.ok(ApiResponse.success(message, updated));
    }

    /**
     * Kháng nghị việc link bị chặn (chỉ chủ link, link phải đang BLOCKED, mỗi link một kháng nghị đang chờ).
     * Trả về link kèm trạng thái kháng nghị. Mỗi request đều tính vào giới hạn theo user.
     */
    @PostMapping("/{shortCode}/appeal")
    public ResponseEntity<ApiResponse<UrlResponse>> appeal(
            @PathVariable String shortCode,
            @Valid @RequestBody AppealRequest body,
            HttpServletRequest request
    ) {
        UUID userId = SecurityUtils.getAuthenticatedUserId(request);
        rateLimitService.check("user-appeal", userId.toString(), rateLimitProperties.getAppealPerUser());
        linkAppealService.submit(userId, shortCode, body.getMessage());
        String message = translator.toLocale("api.success.appeal_sent");
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(message, urlService.get(userId, shortCode)));
    }

    /**
     * Mã QR của short URL dưới dạng PNG. {@code size} ngoài khoảng cho phép được kẹp về
     * [{@value QrCodeGenerator#MIN_SIZE}, {@value QrCodeGenerator#MAX_SIZE}]. Nội dung chỉ phụ thuộc mã
     * của link nên cho phép trình duyệt cache riêng một giờ.
     */
    @GetMapping("/{shortCode}/qr")
    public ResponseEntity<byte[]> qrCode(
            @PathVariable String shortCode,
            @RequestParam(defaultValue = "" + QrCodeGenerator.DEFAULT_SIZE) int size,
            HttpServletRequest request
    ) {
        UUID userId = SecurityUtils.getAuthenticatedUserId(request);
        int clamped = Math.min(Math.max(size, QrCodeGenerator.MIN_SIZE), QrCodeGenerator.MAX_SIZE);
        byte[] png = urlService.qrCode(userId, shortCode, clamped);
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePrivate())
                .body(png);
    }

    /**
     * Thống kê click của link (chỉ chủ link). {@code from}/{@code to} dạng {@code yyyy-MM-dd}, cả hai đầu
     * đều tính; mặc định 30 ngày gần nhất, tối đa {@value UrlStatsService#MAX_DAYS} ngày.
     */
    @GetMapping("/{shortCode}/stats")
    public ResponseEntity<ApiResponse<UrlStatsResponse>> stats(
            @PathVariable String shortCode,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            HttpServletRequest request
    ) {
        UUID userId = SecurityUtils.getAuthenticatedUserId(request);
        return ResponseEntity.ok(ApiResponse.success(null, urlStatsService.getOwnerStats(userId, shortCode, from, to)));
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
