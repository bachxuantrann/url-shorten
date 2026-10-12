package com.auradev.url_shortener.service.impl;

import com.auradev.url_shortener.dto.response.PageResponse;
import com.auradev.url_shortener.dto.response.UserResponse;
import com.auradev.url_shortener.entity.User;
import com.auradev.url_shortener.enums.RoleEnum;
import com.auradev.url_shortener.enums.UserStatusEnum;
import com.auradev.url_shortener.exception.AppException;
import com.auradev.url_shortener.exception.ErrorCode;
import com.auradev.url_shortener.mapper.UserMapper;
import com.auradev.url_shortener.repository.UserRepository;
import com.auradev.url_shortener.service.AdminService;
import com.auradev.url_shortener.utils.LikePatterns;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import com.auradev.url_shortener.config.JwtProperties;
import com.auradev.url_shortener.utils.TokenRedisService;
import java.time.Duration;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminServiceImpl  implements AdminService {
    private final UserRepository userRepository;
    private final UserMapper userMapper;
    private final TokenRedisService tokenRedisService;
    private final JwtProperties jwtProperties;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<UserResponse> getAllUsers(
            String keyword,
            UserStatusEnum status,
            RoleEnum role,
            Pageable pageable
    ) {
        Page<User> page;

        boolean hasKeyword = StringUtils.hasText(keyword);
        boolean hasStatus  = status != null;
        boolean hasRole    = role != null;

        if (hasKeyword || hasStatus || hasRole) {
            // Dùng query filter tổng hợp
            page = userRepository.findWithFilters(
                    LikePatterns.contains(keyword),
                    hasStatus  ? status  : null,
                    hasRole    ? role    : null,
                    pageable
            );
        } else {
            // Không có filter — lấy tất cả
            page = userRepository.findAll(pageable);
        }

        Page<UserResponse> responsePage = page.map(userMapper::toUserResponse);
        return PageResponse.of(responsePage);
    }

    @Override
    @Transactional(readOnly = true)
    public UserResponse getUserById(UUID userId) {
        return userMapper.toUserResponse(findUserByIdOrThrow(userId));
    }

    @Override
    @Transactional
    public void lockUser(UUID userId) {
        User user = findUserByIdOrThrow(userId);
        if (user.getStatus() == UserStatusEnum.BANNED) {
            return; // Đã bị khoá rồi
        }
        userRepository.updateStatus(userId, UserStatusEnum.BANNED);
        // Có hiệu lực ngay: access token đã cấp bị từ chối ở request kế tiếp (không đợi hết hạn). Refresh token giữ nguyên: refresh
        // đã chặn bằng status trong DB (403 ERR_104), nên người quay lại sau khi nghỉ lâu vẫn biết lý do thay vì bị đá ra âm thầm
        tokenRedisService.markUserLocked(userId, Duration.ofMillis(jwtProperties.getAccessTokenExpiry()).plusMinutes(1));
        log.info("[ADMIN] User locked: userId={}", userId);
    }

    @Override
    @Transactional
    public void unlockUser(UUID userId) {
        User user = findUserByIdOrThrow(userId);
        if (user.getStatus() == UserStatusEnum.ACTIVE) {
            return; // Đang active rồi
        }
        userRepository.updateStatus(userId, UserStatusEnum.ACTIVE);
        tokenRedisService.clearUserLocked(userId);
        log.info("[ADMIN] User unlocked: userId={}", userId);
    }

    @Override
    @Transactional
    public UserResponse changeRole(UUID actorId, UUID userId, RoleEnum role) {
        if (actorId.equals(userId)) {
            throw new AppException(ErrorCode.CANNOT_CHANGE_OWN_ROLE);
        }
        User user = findUserByIdOrThrow(userId);
        boolean isAdmin = user.getRoles().contains(RoleEnum.ROLE_ADMIN);
        boolean makeAdmin = role == RoleEnum.ROLE_ADMIN;
        if (isAdmin == makeAdmin) {
            return userMapper.toUserResponse(user); // không đổi gì
        }
        if (!makeAdmin && user.getStatus() == UserStatusEnum.ACTIVE
                && userRepository.countActiveWithRoleExcluding(RoleEnum.ROLE_ADMIN, userId) == 0) {
            throw new AppException(ErrorCode.LAST_ADMIN_REQUIRED);
        }
        user.setRoles(makeAdmin
                ? new HashSet<>(Set.of(RoleEnum.ROLE_ADMIN, RoleEnum.ROLE_USER))
                : new HashSet<>(Set.of(RoleEnum.ROLE_USER)));
        log.info("[ADMIN] User role changed: userId={}, role={}, by={}", userId, makeAdmin ? "ADMIN" : "USER", actorId);
        return userMapper.toUserResponse(userRepository.save(user));
    }

    private User findUserByIdOrThrow(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, userId.toString()));
    }
}
