package com.auradev.url_shortener.service.impl;

import com.auradev.url_shortener.config.JwtProperties;
import com.auradev.url_shortener.dto.request.LoginRequest;
import com.auradev.url_shortener.dto.request.RefreshTokenRequest;
import com.auradev.url_shortener.dto.request.RegisterRequest;
import com.auradev.url_shortener.dto.response.LoginResponse;
import com.auradev.url_shortener.dto.response.TokenResponse;
import com.auradev.url_shortener.dto.response.UserResponse;
import com.auradev.url_shortener.entity.User;
import com.auradev.url_shortener.enums.RoleEnum;
import com.auradev.url_shortener.enums.UserStatusEnum;
import com.auradev.url_shortener.exception.AppException;
import com.auradev.url_shortener.exception.ErrorCode;
import com.auradev.url_shortener.mapper.UserMapper;
import com.auradev.url_shortener.repository.UserRepository;
import com.auradev.url_shortener.service.AuthService;
import com.auradev.url_shortener.utils.JwtService;
import com.auradev.url_shortener.utils.TokenRedisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;

/**
 * Triển khai {@link AuthService} — xử lý đăng ký, đăng nhập, refresh token, đăng xuất.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final UserRepository    userRepository;
    private final UserMapper        userMapper;
    private final PasswordEncoder   passwordEncoder;
    private final JwtService        jwtService;
    private final JwtProperties     jwtProperties;
    private final TokenRedisService tokenRedisService;

    // ===========================
    //  REGISTER
    // ===========================

    @Override
    @Transactional
    public UserResponse register(RegisterRequest request) {
        // 1. Kiểm tra username đã tồn tại
        if (userRepository.existsByUsername(request.getUsername())) {
            throw new AppException(ErrorCode.USERNAME_EXISTS, request.getUsername());
        }

        // 2. Kiểm tra email đã tồn tại
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new AppException(ErrorCode.EMAIL_EXISTS, request.getEmail());
        }

        // 3. Map request sang entity, hash password
        User user = userMapper.toUser(request);
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setStatus(UserStatusEnum.ACTIVE);
        user.setRoles(Set.of(RoleEnum.ROLE_USER));
        user.setEmailVerified(false);

        // 4. Lưu vào DB
        User saved = userRepository.save(user);
        log.info("New user registered: username={}, id={}", saved.getUsername(), saved.getId());

        return userMapper.toUserResponse(saved);
    }

    // ===========================
    //  LOGIN
    // ===========================

    @Override
    @Transactional
    public LoginResponse login(LoginRequest request) {
        // 1. Tìm user theo username
        User user = userRepository.findByUsername(request.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.INVALID_CREDENTIALS));

        // 2. Kiểm tra password
        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new AppException(ErrorCode.INVALID_CREDENTIALS);
        }

        // 3. Kiểm tra trạng thái tài khoản
        validateAccountStatus(user);

        // 4. Tạo tokens
        String accessToken  = jwtService.generateAccessToken(user);
        String refreshToken = jwtService.generateRefreshToken(user);

        // 5. Lưu refresh token vào Redis
        //    (Login mới sẽ ghi đè RT cũ — single-session policy)
        tokenRedisService.saveRefreshToken(
                user.getId(),
                refreshToken,
                Duration.ofMillis(jwtProperties.getRefreshTokenExpiry())
        );

        // 6. Cập nhật lastLoginAt
        userRepository.updateLastLoginAt(user.getId(), LocalDateTime.now());

        log.info("User logged in: username={}", user.getUsername());

        return LoginResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .tokenType("Bearer")
                .expiresIn(jwtService.getAccessTokenExpirySeconds())
                .user(userMapper.toUserResponse(user))
                .build();
    }

    // ===========================
    //  REFRESH TOKEN
    // ===========================

    @Override
    @Transactional(readOnly = true)
    public TokenResponse refreshToken(RefreshTokenRequest request) {
        String refreshToken = request.getRefreshToken();

        // 1. Validate chữ ký và thời hạn của refresh token
        UUID userId;
        try {
            jwtService.isTokenValid(refreshToken);
            userId = jwtService.extractUserId(refreshToken);
        } catch (Exception e) {
            throw new AppException(ErrorCode.INVALID_TOKEN);
        }

        // 2. So sánh với refresh token đang lưu trong Redis
        if (!tokenRedisService.isRefreshTokenValid(userId, refreshToken)) {
            throw new AppException(ErrorCode.REFRESH_TOKEN_MISMATCH);
        }

        // 3. Lấy user từ DB để tạo access token mới
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, userId));

        // 4. Kiểm tra trạng thái tài khoản
        validateAccountStatus(user);

        // 5. Tạo access token mới
        String newAccessToken = jwtService.generateAccessToken(user);

        log.debug("Access token refreshed for userId={}", userId);

        return TokenResponse.builder()
                .accessToken(newAccessToken)
                .tokenType("Bearer")
                .expiresIn(jwtService.getAccessTokenExpirySeconds())
                .build();
    }

    // ===========================
    //  LOGOUT
    // ===========================

    /**
     * Logout thiết bị hiện tại.
     *
     * <p>Logic:
     * <ol>
     *   <li>Blacklist access token hiện tại theo jti — request tiếp theo với AT này bị reject ngay.</li>
     *   <li>Xoá refresh token khỏi Redis — không thể refresh nữa.</li>
     * </ol>
     *
     * <p>Lưu ý (single-session model): {@code RT:{userId}} chỉ có một → xóa RT đồng nghĩa
     * với logout tất cả thiết bị về mặt refresh. Các AT khác đang tồn tại (nếu có) sẽ
     * tự expire theo TTL. Dùng {@link #logoutAllDevices} nếu muốn invalidate AT ngay lập tức.
     */
    @Override
    public void logout(String accessToken) {
        try {
            // 1. Blacklist access token hiện tại (TTL = thời gian còn lại)
            String   jti          = jwtService.extractJti(accessToken);
            Duration remainingTtl = jwtService.getRemainingTtl(accessToken);
            tokenRedisService.blacklistAccessToken(jti, remainingTtl);

            // 2. Xoá refresh token — không thể refresh session này nữa
            UUID userId = jwtService.extractUserId(accessToken);
            tokenRedisService.deleteRefreshToken(userId);

            log.info("User logged out (single device): userId={}", userId);
        } catch (Exception e) {
            // Token đã hết hạn hoặc invalid — coi như đã logout, không throw lỗi
            log.debug("Logout with invalid/expired token: {}", e.getMessage());
        }
    }

    /**
     * Logout toàn bộ thiết bị — dùng khi nghi ngờ tài khoản bị xâm phạm.
     *
     * <p>Logic:
     * <ol>
     *   <li>Blacklist access token hiện tại theo jti.</li>
     *   <li>Xoá refresh token — không thể refresh từ bất kỳ thiết bị nào.</li>
     *   <li>Set global revoke timestamp — mọi AT có {@code issuedAt ≤ revokedAt}
     *       đều bị {@link com.auradev.url_shortener.security.JwtAuthenticationFilter} reject
     *       ngay lập tức, kể cả AT đang nằm ở các thiết bị khác.</li>
     * </ol>
     *
     * <p>TTL của revoke timestamp = access token max lifetime.
     * Sau đó Redis tự clean vì không còn AT nào issued trước timestamp còn hạn.
     */
    @Override
    public void logoutAllDevices(String accessToken) {
        try {
            UUID userId = jwtService.extractUserId(accessToken);

            // 1. Blacklist access token hiện tại
            String   jti          = jwtService.extractJti(accessToken);
            Duration remainingTtl = jwtService.getRemainingTtl(accessToken);
            tokenRedisService.blacklistAccessToken(jti, remainingTtl);

            // 2. Xoá refresh token — chặn tất cả thiết bị refresh
            tokenRedisService.deleteRefreshToken(userId);

            // 3. Set global revoke timestamp — invalidate tất cả AT đang tồn tại ngay lập tức
            Duration accessTokenMaxTtl = Duration.ofMillis(jwtProperties.getAccessTokenExpiry());
            tokenRedisService.setGlobalRevokeTimestamp(userId, accessTokenMaxTtl);

            log.info("User logged out from all devices: userId={}", userId);
        } catch (Exception e) {
            log.debug("Logout-all with invalid/expired token: {}", e.getMessage());
        }
    }

    // ===========================
    //  PRIVATE HELPERS
    // ===========================

    private void validateAccountStatus(User user) {
        switch (user.getStatus()) {
            case INACTIVE -> throw new AppException(ErrorCode.ACCOUNT_INACTIVE);
            case BANNED   -> throw new AppException(ErrorCode.ACCOUNT_BANNED);
            case ACTIVE   -> { /* OK */ }
        }
    }
}
