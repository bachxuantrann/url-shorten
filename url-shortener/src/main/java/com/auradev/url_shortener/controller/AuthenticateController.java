package com.auradev.url_shortener.controller;

import com.auradev.url_shortener.constant.AppConstant;
import com.auradev.url_shortener.dto.request.LoginRequest;
import com.auradev.url_shortener.dto.request.RefreshTokenRequest;
import com.auradev.url_shortener.dto.request.RegisterRequest;
import com.auradev.url_shortener.dto.response.ApiResponse;
import com.auradev.url_shortener.dto.response.LoginResponse;
import com.auradev.url_shortener.dto.response.TokenResponse;
import com.auradev.url_shortener.dto.response.UserResponse;
import com.auradev.url_shortener.service.AuthService;
import com.auradev.url_shortener.utils.TranslatorUtils;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Controller xử lý các API xác thực (Authentication).
 *
 * <p>Base path: {@code /api/v1/auth}
 * <ul>
 *   <li>POST {@code /register}       — Đăng ký tài khoản mới</li>
 *   <li>POST {@code /login}          — Đăng nhập</li>
 *   <li>POST {@code /refresh-token}  — Làm mới access token</li>
 *   <li>POST {@code /logout}         — Đăng xuất thiết bị hiện tại</li>
 *   <li>POST {@code /logout-all}     — Đăng xuất tất cả thiết bị</li>
 * </ul>
 */
@RestController
@RequestMapping(AppConstant.API_AUTH)
@RequiredArgsConstructor
public class AuthenticateController {

    private final AuthService     authService;
    private final TranslatorUtils translator;

    /**
     * POST /api/v1/auth/register
     * Tạo tài khoản mới — không cần xác thực.
     */
    @PostMapping("/register")
    public ResponseEntity<ApiResponse<UserResponse>> register(
            @Valid @RequestBody RegisterRequest request
    ) {
        UserResponse user    = authService.register(request);
        String message       = translator.toLocale("api.success.user_registered");
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success(message, user));
    }

    /**
     * POST /api/v1/auth/login
     * Đăng nhập — trả về access token và refresh token.
     */
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(
            @Valid @RequestBody LoginRequest request
    ) {
        LoginResponse loginResponse = authService.login(request);
        String message              = translator.toLocale("api.success.login");
        return ResponseEntity.ok(ApiResponse.success(message, loginResponse));
    }

    /**
     * POST /api/v1/auth/refresh-token
     * Làm mới access token — gửi kèm refresh token trong body.
     */
    @PostMapping("/refresh-token")
    public ResponseEntity<ApiResponse<TokenResponse>> refreshToken(
            @Valid @RequestBody RefreshTokenRequest request
    ) {
        TokenResponse tokenResponse = authService.refreshToken(request);
        String message              = translator.toLocale("api.success.token_refreshed");
        return ResponseEntity.ok(ApiResponse.success(message, tokenResponse));
    }

    /**
     * POST /api/v1/auth/logout
     * Đăng xuất thiết bị hiện tại.
     * Access token được lấy từ Authorization header.
     */
    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader
    ) {
        String accessToken = extractToken(authHeader);
        authService.logout(accessToken);
        String message = translator.toLocale("api.success.logout");
        return ResponseEntity.ok(ApiResponse.success(message, null));
    }

    /**
     * POST /api/v1/auth/logout-all
     * Đăng xuất tất cả thiết bị của user hiện tại.
     */
    @PostMapping("/logout-all")
    public ResponseEntity<ApiResponse<Void>> logoutAll(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader
    ) {
        String accessToken = extractToken(authHeader);
        authService.logoutAllDevices(accessToken);
        String message = translator.toLocale("api.success.logout_all");
        return ResponseEntity.ok(ApiResponse.success(message, null));
    }

    // ===========================
    //  PRIVATE HELPERS
    // ===========================

    private String extractToken(String authHeader) {
        if (authHeader != null && authHeader.startsWith(AppConstant.BEARER_PREFIX)) {
            return authHeader.substring(AppConstant.BEARER_PREFIX.length());
        }
        return authHeader;
    }
}
