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
}
