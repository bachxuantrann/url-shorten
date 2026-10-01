package com.auradev.url_shortener.service;

import com.auradev.url_shortener.dto.request.ChangePasswordRequest;
import com.auradev.url_shortener.dto.request.UpdateProfileRequest;
import com.auradev.url_shortener.dto.response.PageResponse;
import com.auradev.url_shortener.dto.response.UserResponse;
import com.auradev.url_shortener.enums.RoleEnum;
import com.auradev.url_shortener.enums.UserStatusEnum;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

/**
 * Interface xử lý nghiệp vụ người dùng (User management).
 */
public interface UserService {

    // ===== USER SELF-SERVICE =====

    /**
     * Lấy thông tin profile của user hiện tại.
     *
     * @param userId ID của user
     * @return thông tin user
     */
    UserResponse getProfile(UUID userId);

    /**
     * Cập nhật thông tin profile.
     *
     * @param userId  ID của user
     * @param request thông tin cần cập nhật
     * @return thông tin user sau khi cập nhật
     */
    UserResponse updateProfile(UUID userId, UpdateProfileRequest request);

    /**
     * Đổi mật khẩu.
     * Kiểm tra mật khẩu cũ và xác nhận mật khẩu mới trùng khớp.
     *
     * @param userId  ID của user
     * @param request chứa oldPassword, newPassword, confirmPassword
     */
    void changePassword(UUID userId, ChangePasswordRequest request);
}
