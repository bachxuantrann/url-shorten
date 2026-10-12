package com.auradev.url_shortener.service;

import com.auradev.url_shortener.dto.response.PageResponse;
import com.auradev.url_shortener.dto.response.UserResponse;
import com.auradev.url_shortener.enums.RoleEnum;
import com.auradev.url_shortener.enums.UserStatusEnum;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface AdminService {
    // ===== ADMIN =====

    /**
     * Lấy danh sách user có phân trang và filter.
     *
     * @param keyword  từ khoá tìm kiếm theo username/email/fullName (nullable)
     * @param status   lọc theo trạng thái (nullable = all)
     * @param role     lọc theo vai trò (nullable = all)
     * @param pageable thông tin phân trang và sắp xếp
     * @return page users
     */
    PageResponse<UserResponse> getAllUsers(
            String keyword,
            UserStatusEnum status,
            RoleEnum role,
            Pageable pageable
    );

    /**
     * Lấy thông tin user theo ID (Admin).
     *
     * @param userId ID cần tìm
     * @return thông tin user
     */
    UserResponse getUserById(UUID userId);

    /**
     * Khoá tài khoản user (Admin).
     * Set status = BANNED.
     *
     * @param userId ID của user cần khoá
     */
    void lockUser(UUID userId);

    /**
     * Mở khoá tài khoản user (Admin).
     * Set status = ACTIVE.
     *
     * @param userId ID của user cần mở khoá
     */
    void unlockUser(UUID userId);

    /**
     * Đổi vai trò (Admin): {@code ROLE_ADMIN} = {ADMIN, USER}, {@code ROLE_USER} = {USER}. Không tự đổi vai trò của mình
     * ({@code CANNOT_CHANGE_OWN_ROLE}) và không gỡ quản trị viên ACTIVE cuối cùng ({@code LAST_ADMIN_REQUIRED}). Role nằm
     * trong JWT nên có hiệu lực khi access token hiện tại của user hết hạn.
     *
     * @param actorId admin đang thao tác
     */
    UserResponse changeRole(UUID actorId, UUID userId, RoleEnum role);
}
