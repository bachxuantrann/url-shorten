package com.auradev.url_shortener.service.impl;

import com.auradev.url_shortener.dto.request.ChangePasswordRequest;
import com.auradev.url_shortener.dto.request.UpdateProfileRequest;
import com.auradev.url_shortener.dto.response.PageResponse;
import com.auradev.url_shortener.dto.response.UserResponse;
import com.auradev.url_shortener.entity.User;
import com.auradev.url_shortener.enums.RoleEnum;
import com.auradev.url_shortener.enums.UserStatusEnum;
import com.auradev.url_shortener.exception.AppException;
import com.auradev.url_shortener.exception.ErrorCode;
import com.auradev.url_shortener.mapper.UserMapper;
import com.auradev.url_shortener.repository.UserRepository;
import com.auradev.url_shortener.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.UUID;

/**
 * Triển khai {@link UserService} — quản lý profile và Admin operations.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserRepository  userRepository;
    private final UserMapper      userMapper;
    private final PasswordEncoder passwordEncoder;

    // ===========================
    //  USER SELF-SERVICE
    // ===========================

    @Override
    @Transactional(readOnly = true)
    public UserResponse getProfile(UUID userId) {
        User user = findUserByIdOrThrow(userId);
        return userMapper.toUserResponse(user);
    }

    @Override
    @Transactional
    public UserResponse updateProfile(UUID userId, UpdateProfileRequest request) {
        User user = findUserByIdOrThrow(userId);

        // Kiểm tra email mới có bị trùng không (nếu đổi email)
        if (!user.getEmail().equals(request.getEmail())
                && userRepository.existsByEmailAndIdNot(request.getEmail(), userId)) {
            throw new AppException(ErrorCode.EMAIL_EXISTS, request.getEmail());
        }

        // Map các field được phép thay đổi
        userMapper.updateUserFromRequest(request, user);

        // Nếu đổi email → cần xác minh lại (reset emailVerified)
        if (!user.getEmail().equals(request.getEmail())) {
            user.setEmailVerified(false);
        }

        User updated = userRepository.save(user);
        log.info("Profile updated for userId={}", userId);
        return userMapper.toUserResponse(updated);
    }

    @Override
    @Transactional
    public void changePassword(UUID userId, ChangePasswordRequest request) {
        User user = findUserByIdOrThrow(userId);

        // 1. Kiểm tra mật khẩu cũ
        if (!passwordEncoder.matches(request.getOldPassword(), user.getPassword())) {
            throw new AppException(ErrorCode.WRONG_OLD_PASSWORD);
        }

        // 2. Kiểm tra newPassword == confirmPassword
        if (!request.getNewPassword().equals(request.getConfirmPassword())) {
            throw new AppException(ErrorCode.PASSWORD_NOT_MATCH);
        }

        // 3. Cập nhật mật khẩu mới (hash bằng BCrypt)
        user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(user);

        log.info("Password changed for userId={}", userId);
    }

    // ===========================
    //  PRIVATE HELPERS
    // ===========================

    private User findUserByIdOrThrow(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, userId.toString()));
    }
}
