package com.auradev.url_shortener.controller;

import com.auradev.url_shortener.constant.AppConstant;
import com.auradev.url_shortener.dto.request.ChangePasswordRequest;
import com.auradev.url_shortener.dto.request.UpdateProfileRequest;
import com.auradev.url_shortener.dto.response.ApiResponse;
import com.auradev.url_shortener.dto.response.PageResponse;
import com.auradev.url_shortener.dto.response.UserResponse;
import com.auradev.url_shortener.enums.RoleEnum;
import com.auradev.url_shortener.enums.UserStatusEnum;
import com.auradev.url_shortener.service.UserService;
import com.auradev.url_shortener.utils.TranslatorUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Controller quản lý tài khoản người dùng.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>GET    {@code /api/v1/users/me}                     — Lấy profile của mình</li>
 *   <li>PUT    {@code /api/v1/users/me}                     — Cập nhật profile</li>
 *   <li>PUT    {@code /api/v1/users/me/change-password}     — Đổi mật khẩu</li>
 *   <li>GET    {@code /api/v1/admin/users}                  — [ADMIN] Danh sách users (filter + phân trang)</li>
 *   <li>GET    {@code /api/v1/admin/users/{id}}             — [ADMIN] Lấy user theo ID</li>
 *   <li>PUT    {@code /api/v1/admin/users/{id}/lock}        — [ADMIN] Khoá tài khoản</li>
 *   <li>PUT    {@code /api/v1/admin/users/{id}/unlock}      — [ADMIN] Mở khoá tài khoản</li>
 * </ul>
 */
@RestController
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    // ===========================
    //  USER SELF-SERVICE
    // ===========================

    /**
     * GET /api/v1/users/me
     * Lấy thông tin profile của user đang đăng nhập.
     * userId được lấy từ request attribute đã set bởi {@link com.auradev.url_shortener.security.JwtAuthenticationFilter}.
     */
    @GetMapping(AppConstant.API_USERS + "/me")
    public ResponseEntity<ApiResponse<UserResponse>> getMyProfile(HttpServletRequest request) {
        UUID userId = getAuthenticatedUserId(request);
        UserResponse profile = userService.getProfile(userId);
        return ResponseEntity.ok(ApiResponse.success(null, profile));
    }

    /**
     * PUT /api/v1/users/me
     * Cập nhật thông tin cá nhân.
     */
    @PutMapping(AppConstant.API_USERS + "/me")
    public ResponseEntity<ApiResponse<UserResponse>> updateMyProfile(
            @Valid @RequestBody UpdateProfileRequest updateRequest,
            HttpServletRequest request
    ) {
        UUID userId = getAuthenticatedUserId(request);
        UserResponse updated = userService.updateProfile(userId, updateRequest);
        String message = TranslatorUtils.toLocale("api.success.profile_updated");
        return ResponseEntity.ok(ApiResponse.success(message, updated));
    }

    /**
     * PUT /api/v1/users/me/change-password
     * Đổi mật khẩu.
     */
    @PutMapping(AppConstant.API_USERS + "/me/change-password")
    public ResponseEntity<ApiResponse<Void>> changePassword(
            @Valid @RequestBody ChangePasswordRequest changeRequest,
            HttpServletRequest request
    ) {
        UUID userId = getAuthenticatedUserId(request);
        userService.changePassword(userId, changeRequest);
        String message = TranslatorUtils.toLocale("api.success.password_changed");
        return ResponseEntity.ok(ApiResponse.success(message, null));
    }

    // ===========================
    //  ADMIN
    // ===========================

    /**
     * GET /api/v1/admin/users
     * Danh sách users có phân trang và filter.
     *
     * @param keyword  tìm kiếm theo username/email/fullName (optional)
     * @param status   lọc theo UserStatusEnum (optional)
     * @param role     lọc theo RoleEnum (optional)
     * @param page     số trang (0-indexed, default: 0)
     * @param size     số phần tử mỗi trang (default: 20, max: 100)
     * @param sortBy   field sắp xếp (default: createdAt)
     * @param sortDir  hướng sắp xếp: asc/desc (default: desc)
     */
    @GetMapping(AppConstant.API_ADMIN_USERS)
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<PageResponse<UserResponse>>> getAllUsers(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) UserStatusEnum status,
            @RequestParam(required = false) RoleEnum role,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir
    ) {
        // Giới hạn page size tối đa
        size = Math.min(size, AppConstant.MAX_PAGE_SIZE);

        Sort sort     = sortDir.equalsIgnoreCase("asc")
                ? Sort.by(sortBy).ascending()
                : Sort.by(sortBy).descending();
        Pageable pageable = PageRequest.of(page, size, sort);

        PageResponse<UserResponse> result = userService.getAllUsers(keyword, status, role, pageable);
        return ResponseEntity.ok(ApiResponse.success(null, result));
    }

    /**
     * GET /api/v1/admin/users/{id}
     * Lấy thông tin user theo ID.
     */
    @GetMapping(AppConstant.API_ADMIN_USERS + "/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<UserResponse>> getUserById(@PathVariable UUID id) {
        UserResponse user = userService.getUserById(id);
        return ResponseEntity.ok(ApiResponse.success(null, user));
    }

    /**
     * PUT /api/v1/admin/users/{id}/lock
     * Khoá tài khoản user (set status = BANNED).
     */
    @PutMapping(AppConstant.API_ADMIN_USERS + "/{id}/lock")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Void>> lockUser(@PathVariable UUID id) {
        userService.lockUser(id);
        String message = TranslatorUtils.toLocale("api.success.user_locked");
        return ResponseEntity.ok(ApiResponse.success(message, null));
    }

    /**
     * PUT /api/v1/admin/users/{id}/unlock
     * Mở khoá tài khoản user (set status = ACTIVE).
     */
    @PutMapping(AppConstant.API_ADMIN_USERS + "/{id}/unlock")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Void>> unlockUser(@PathVariable UUID id) {
        userService.unlockUser(id);
        String message = TranslatorUtils.toLocale("api.success.user_unlocked");
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
