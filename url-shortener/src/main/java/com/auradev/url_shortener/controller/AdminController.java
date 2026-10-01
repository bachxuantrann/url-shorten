package com.auradev.url_shortener.controller;

import com.auradev.url_shortener.constant.AppConstant;
import com.auradev.url_shortener.dto.response.ApiResponse;
import com.auradev.url_shortener.dto.response.PageResponse;
import com.auradev.url_shortener.dto.response.UserResponse;
import com.auradev.url_shortener.enums.RoleEnum;
import com.auradev.url_shortener.enums.UserStatusEnum;
import com.auradev.url_shortener.service.AdminService;
import com.auradev.url_shortener.service.UserService;
import com.auradev.url_shortener.utils.TranslatorUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Controller xử lý các thao tác quản trị dành cho ADMIN.
 *
 * <p>Tất cả endpoints đều yêu cầu role {@code ROLE_ADMIN}.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>GET  {@code /api/v1/admin/users}           — Danh sách users (filter + phân trang)</li>
 *   <li>GET  {@code /api/v1/admin/users/{id}}      — Lấy user theo ID</li>
 *   <li>PUT  {@code /api/v1/admin/users/{id}/lock}   — Khoá tài khoản</li>
 *   <li>PUT  {@code /api/v1/admin/users/{id}/unlock} — Mở khoá tài khoản</li>
 * </ul>
 */
@RestController
@RequestMapping(AppConstant.API_ADMIN)
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminController {

    private final UserService     userService;
    private final AdminService adminService;
    private final TranslatorUtils translator;

    // ===========================
    //  USER MANAGEMENT
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
    @GetMapping("/users")
    public ResponseEntity<ApiResponse<PageResponse<UserResponse>>> getAllUsers(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) UserStatusEnum status,
            @RequestParam(required = false) RoleEnum role,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir
    ) {
        size = Math.min(size, AppConstant.MAX_PAGE_SIZE);

        Sort sort = sortDir.equalsIgnoreCase("asc")
                ? Sort.by(sortBy).ascending()
                : Sort.by(sortBy).descending();
        Pageable pageable = PageRequest.of(page, size, sort);

        PageResponse<UserResponse> result = adminService.getAllUsers(keyword, status, role, pageable);
        return ResponseEntity.ok(ApiResponse.success(null, result));
    }

    /**
     * GET /api/v1/admin/users/{id}
     * Lấy thông tin user theo ID.
     */
    @GetMapping("/users/{id}")
    public ResponseEntity<ApiResponse<UserResponse>> getUserById(@PathVariable UUID id) {
        UserResponse user = adminService.getUserById(id);
        return ResponseEntity.ok(ApiResponse.success(null, user));
    }

    /**
     * PUT /api/v1/admin/users/{id}/lock
     * Khoá tài khoản user (set status = BANNED).
     */
    @PutMapping("/users/{id}/lock")
    public ResponseEntity<ApiResponse<Void>> lockUser(@PathVariable UUID id) {
        adminService.lockUser(id);
        String message = translator.toLocale("api.success.user_locked");
        return ResponseEntity.ok(ApiResponse.success(message, null));
    }

    /**
     * PUT /api/v1/admin/users/{id}/unlock
     * Mở khoá tài khoản user (set status = ACTIVE).
     */
    @PutMapping("/users/{id}/unlock")
    public ResponseEntity<ApiResponse<Void>> unlockUser(@PathVariable UUID id) {
        adminService.unlockUser(id);
        String message = translator.toLocale("api.success.user_unlocked");
        return ResponseEntity.ok(ApiResponse.success(message, null));
    }
}
