package com.auradev.url_shortener.controller;

import com.auradev.url_shortener.config.RateLimitProperties;
import com.auradev.url_shortener.constant.AppConstant;
import com.auradev.url_shortener.dto.request.CreateUrlRequest;
import com.auradev.url_shortener.dto.response.ApiResponse;
import com.auradev.url_shortener.dto.response.UrlResponse;
import com.auradev.url_shortener.service.UrlService;
import com.auradev.url_shortener.utils.ClientIpUtils;
import com.auradev.url_shortener.utils.RateLimitService;
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
 * Tạo link không cần đăng nhập: {@code POST /api/v1/public/urls}.
 *
 * <p>Link ẩn danh không thuộc về ai nên không quản lý được qua API người dùng. Chống lạm dụng bằng
 * rate limit theo IP (mặc định 10 link/giờ, trả 429 kèm {@code Retry-After}). Mọi request đều được
 * tính vào giới hạn, kể cả request bị từ chối vì URL không hợp lệ, để chặn việc dò thử hàng loạt.
 * Token (nếu có) bị bỏ qua: endpoint này luôn tạo link ẩn danh.
 */
@RestController
@RequestMapping(AppConstant.API_PUBLIC + "/urls")
@RequiredArgsConstructor
public class PublicUrlController {

    private final UrlService          urlService;
    private final TranslatorUtils     translator;
    private final RateLimitService    rateLimitService;
    private final RateLimitProperties rateLimitProperties;

    @PostMapping
    public ResponseEntity<ApiResponse<UrlResponse>> create(
            @Valid @RequestBody CreateUrlRequest body,
            HttpServletRequest request
    ) {
        rateLimitService.check("anon-create", ClientIpUtils.resolve(request), rateLimitProperties.getAnonymousCreate());

        UrlResponse created = urlService.createAnonymous(body);
        String message = translator.toLocale("api.success.url_created");
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(message, created));
    }
}
