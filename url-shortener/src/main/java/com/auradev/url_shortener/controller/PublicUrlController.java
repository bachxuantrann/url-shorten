package com.auradev.url_shortener.controller;

import com.auradev.url_shortener.config.RateLimitProperties;
import com.auradev.url_shortener.constant.AppConstant;
import com.auradev.url_shortener.dto.request.CreatePublicUrlRequest;
import com.auradev.url_shortener.dto.request.CreateUrlRequest;
import com.auradev.url_shortener.dto.request.UnlockLinkRequest;
import com.auradev.url_shortener.dto.response.ApiResponse;
import com.auradev.url_shortener.dto.response.UnlockLinkResponse;
import com.auradev.url_shortener.dto.response.UrlResponse;
import com.auradev.url_shortener.exception.AppException;
import com.auradev.url_shortener.exception.ErrorCode;
import com.auradev.url_shortener.service.ClickPublisher;
import com.auradev.url_shortener.service.RedirectService;
import com.auradev.url_shortener.service.UrlService;
import com.auradev.url_shortener.utils.ClientIpUtils;
import com.auradev.url_shortener.utils.RateLimitService;
import com.auradev.url_shortener.utils.ShortCodes;
import com.auradev.url_shortener.utils.TranslatorUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Các endpoint công khai của link (không cần đăng nhập), base path {@code /api/v1/public/urls}.
 *
 * <ul>
 *   <li>POST {@code /}                   — Tạo link ẩn danh (rate limit theo IP)</li>
 *   <li>POST {@code /{shortCode}/unlock} — Nhập mật khẩu để lấy URL đích của link được bảo vệ</li>
 * </ul>
 *
 * <p><b>Link ẩn danh</b> không thuộc về ai nên không quản lý được qua API người dùng; chỉ có URL đích và
 * hạn tuỳ chọn, <b>không có alias hay mật khẩu</b> (field đó bị bỏ qua nếu client gửi kèm). Mọi request
 * tạo đều được tính vào giới hạn, kể cả request bị từ chối vì URL không hợp lệ, để chặn việc dò thử hàng
 * loạt. Token (nếu có) bị bỏ qua.
 *
 * <p><b>Unlock</b>: {@code GET /{shortCode}} của link có mật khẩu trả 403 {@code ERR_408}; client (trang
 * nhập mật khẩu) gọi endpoint này rồi tự chuyển hướng tới {@code originalUrl} nhận được. Giới hạn thử
 * theo ba lớp: theo IP, theo (link, IP) và theo link, chống đoán mật khẩu cả từ một nguồn lẫn phân tán.
 * Đánh đổi: ai đó có thể dùng hết ngân sách theo link của một link bị bảo vệ để làm khách hợp lệ chờ tối
 * đa một giờ; cần nhiều IP khác nhau để làm vậy và chỉ ảnh hưởng việc mở khoá, không ảnh hưởng link
 * không có mật khẩu.
 */
@RestController
@RequestMapping(AppConstant.API_PUBLIC + "/urls")
@RequiredArgsConstructor
public class PublicUrlController {

    private final UrlService          urlService;
    private final RedirectService     redirectService;
    private final TranslatorUtils     translator;
    private final RateLimitService    rateLimitService;
    private final RateLimitProperties rateLimitProperties;
    private final ClickPublisher      clickPublisher;

    @PostMapping
    public ResponseEntity<ApiResponse<UrlResponse>> create(
            @Valid @RequestBody CreatePublicUrlRequest body,
            HttpServletRequest request
    ) {
        rateLimitService.check("anon-create", ClientIpUtils.resolve(request), rateLimitProperties.getAnonymousCreate());

        CreateUrlRequest anonymous = new CreateUrlRequest();
        anonymous.setOriginalUrl(body.getOriginalUrl());
        anonymous.setExpiresAt(body.getExpiresAt());

        UrlResponse created = urlService.createAnonymous(anonymous);
        String message = translator.toLocale("api.success.url_created");
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(message, created));
    }

    @PostMapping("/{shortCode}/unlock")
    public ResponseEntity<ApiResponse<UnlockLinkResponse>> unlock(
            @PathVariable String shortCode,
            @Valid @RequestBody UnlockLinkRequest body,
            HttpServletRequest request
    ) {
        // Mã không thể tồn tại thì từ chối trước khi tạo khoá rate limit: chặn việc tạo vô hạn khoá Redis
        // từ các mã bịa
        if (!ShortCodes.isResolvable(shortCode)) {
            throw new AppException(ErrorCode.URL_NOT_FOUND, shortCode);
        }

        String ip = ClientIpUtils.resolve(request);
        rateLimitService.check("unlock-ip", ip, rateLimitProperties.getUnlockPerIp());
        rateLimitService.check("unlock-link-ip", shortCode + ":" + ip, rateLimitProperties.getUnlockPerLinkAndIp());
        rateLimitService.check("unlock-link", shortCode, rateLimitProperties.getUnlockPerLink());

        String target = redirectService.unlockTarget(shortCode, body.getPassword());
        // Unlock đúng mật khẩu là lượt truy cập thành công của link có mật khẩu (GET /{code} chỉ trả 403)
        clickPublisher.publish(shortCode, request);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(null, new UnlockLinkResponse(target)));
    }
}
