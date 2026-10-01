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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminServiceImpl  implements AdminService {
    private final UserRepository userRepository;
    private final UserMapper userMapper;

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
                    hasKeyword ? keyword : null,
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
        log.info("[ADMIN] User unlocked: userId={}", userId);
    }

    private User findUserByIdOrThrow(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, userId.toString()));
    }
}
