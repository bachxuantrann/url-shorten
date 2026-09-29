package com.auradev.url_shortener.controller;

import com.auradev.url_shortener.constant.AppConstant;
import com.auradev.url_shortener.dto.request.ChangePasswordRequest;
import com.auradev.url_shortener.dto.request.UpdateProfileRequest;
import com.auradev.url_shortener.dto.response.ApiResponse;
import com.auradev.url_shortener.dto.response.UserResponse;
import com.auradev.url_shortener.service.UserService;
import com.auradev.url_shortener.utils.TranslatorUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Controller quản lý tài khoản người dùng (self-service).
 *
 * <p>Endpoints:
 * <ul>
 *   <li>GET {@code /api/v1/users/me}                 — Lấy profile của mình</li>
 *   <li>PUT {@code /api/v1/users/me}                 — Cập nhật profile</li>
 *   <li>PUT {@code /api/v1/users/me/change-password} — Đổi mật khẩu</li>
 * </ul>
 */
@RestController
@RequestMapping(AppConstant.API_USERS)
@RequiredArgsConstructor
public class UserController {

    private final UserService     userService;
    private final TranslatorUtils translator;

    /**
     * GET /api/v1/users/me
     * Lấy thông tin profile của user đang đăng nhập.
     * userId được lấy từ request attribute đã set bởi {@link com.auradev.url_shortener.security.JwtAuthenticationFilter}.
     */
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<UserResponse>> getMyProfile(HttpServletRequest request) {
        UUID userId = getAuthenticatedUserId(request);
        UserResponse profile = userService.getProfile(userId);
        return ResponseEntity.ok(ApiResponse.success(null, profile));
    }

    /**
     * PUT /api/v1/users/me
     * Cập nhật thông tin cá nhân.
     */
    @PutMapping("/me")
    public ResponseEntity<ApiResponse<UserResponse>> updateMyProfile(
            @Valid @RequestBody UpdateProfileRequest updateRequest,
            HttpServletRequest request
    ) {
        UUID userId = getAuthenticatedUserId(request);
        UserResponse updated = userService.updateProfile(userId, updateRequest);
        String message = translator.toLocale("api.success.profile_updated");
        return ResponseEntity.ok(ApiResponse.success(message, updated));
    }

    /**
     * PUT /api/v1/users/me/change-password
     * Đổi mật khẩu.
     */
    @PutMapping("/me/change-password")
    public ResponseEntity<ApiResponse<Void>> changePassword(
            @Valid @RequestBody ChangePasswordRequest changeRequest,
            HttpServletRequest request
    ) {
        UUID userId = getAuthenticatedUserId(request);
        userService.changePassword(userId, changeRequest);
        String message = translator.toLocale("api.success.password_changed");
        return ResponseEntity.ok(ApiResponse.success(message, null));
    }

    // ===========================
    //  PRIVATE HELPERS
    // ===========================

    /**
     * Lấy userId từ request attribute đã được set bởi {@link com.auradev.url_shortener.security.JwtAuthenticationFilter}.
     * Đảm bảo Controller không cần parse JWT thủ công.
     */
    private UUID getAuthenticatedUserId(HttpServletRequest request) {
        Object userId = request.getAttribute("authenticatedUserId");
        if (userId instanceof UUID) {
            return (UUID) userId;
        }
        throw new IllegalStateException("User ID not found in request context");
    }
}
