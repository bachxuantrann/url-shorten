package com.auradev.url_shortener.utils;

import com.auradev.url_shortener.constant.AppConstant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Service quản lý JWT token trong Redis.
 *
 * <p>Các loại key Redis:
 * <ul>
 *   <li>{@code RT:{userId}:{sessionId}} → refresh token string, TTL = refreshTokenExpiry</li>
 *   <li>{@code BL:{jti}}                → "1" (revoked marker), TTL = thời gian còn lại của access token</li>
 *   <li>{@code LOCK:{userId}}           → "1" (tài khoản bị admin khoá), TTL = thời gian sống của access token</li>
 * </ul>
 *
 * <p>Thiết kế multi-session: mỗi lần login tạo một {@code sessionId} riêng nên một user
 * có thể đăng nhập đồng thời nhiều thiết bị. Logout chỉ xoá RT của session hiện tại
 * và blacklist access token đang dùng. Hiện chưa có "logout all devices".
 *
 * <p>Khoá tài khoản: bộ lọc JWT không query DB nên không tự biết status đã đổi; admin khoá user thì ghi dấu
 * {@code LOCK:{userId}}, nhờ đó mọi access token đã cấp bị từ chối ngay ở request kế tiếp thay vì sống tiếp tới khi hết hạn.
 * Refresh token không bị xoá: refresh đã chặn bằng status trong DB nên trả đúng lỗi "tài khoản bị khoá".
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenRedisService {

    private final RedisTemplate<String, String> redisTemplate;

    // ===========================
    //  REFRESH TOKEN
    // ===========================

    /**
     * Lưu refresh token vào Redis với TTL.
     *
     * @param userId       ID của user (UUID)
     * @param sessionId    ID của session (UUID)
     * @param refreshToken chuỗi refresh token
     * @param ttl          thời gian sống
     */
    public void saveRefreshToken(UUID userId, String sessionId, String refreshToken, Duration ttl) {
        String key = buildRefreshTokenKey(userId, sessionId);
        redisTemplate.opsForValue().set(key, refreshToken, ttl.toMillis(), TimeUnit.MILLISECONDS);
        log.debug("Saved refresh token for user {} session {} with TTL {}s", userId, sessionId, ttl.getSeconds());
    }

    /**
     * Lấy refresh token đang active của user session.
     *
     * @return refresh token string, hoặc {@code null} nếu đã hết hạn / không tồn tại
     */
    public String getRefreshToken(UUID userId, String sessionId) {
        return redisTemplate.opsForValue().get(buildRefreshTokenKey(userId, sessionId));
    }

    /**
     * Kiểm tra refresh token của user có hợp lệ không.
     * So sánh exact với token đang lưu trong Redis.
     */
    public boolean isRefreshTokenValid(UUID userId, String sessionId, String refreshToken) {
        String stored = getRefreshToken(userId, sessionId);
        return stored != null && stored.equals(refreshToken);
    }

    /**
     * Xoá refresh token của user (logout hoặc rotate token).
     */
    public void deleteRefreshToken(UUID userId, String sessionId) {
        Boolean deleted = redisTemplate.delete(buildRefreshTokenKey(userId, sessionId));
        log.debug("Deleted refresh token for user {} session {}: {}", userId, sessionId, deleted);
    }

    // ===========================
    //  ACCESS TOKEN BLACKLIST
    // ===========================

    /**
     * Thêm access token vào blacklist với TTL = thời gian còn lại của token.
     * Sau khi TTL hết, Redis tự xoá — không cần cleanup thủ công.
     *
     * @param jti           JWT ID của access token
     * @param remainingTtl  thời gian còn lại của token
     */
    public void blacklistAccessToken(String jti, Duration remainingTtl) {
        if (remainingTtl.isZero() || remainingTtl.isNegative()) {
            // Token đã hết hạn rồi, không cần blacklist
            return;
        }
        String key = buildBlacklistKey(jti);
        redisTemplate.opsForValue().set(key, "1", remainingTtl.toMillis(), TimeUnit.MILLISECONDS);
        log.debug("Blacklisted access token jti={} for {}s", jti, remainingTtl.getSeconds());
    }

    /**
     * Kiểm tra access token có bị blacklist không.
     *
     * @param jti JWT ID của access token
     * @return {@code true} nếu token đã bị revoke
     */
    public boolean isAccessTokenBlacklisted(String jti) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(buildBlacklistKey(jti)));
    }

    // ===========================
    //  KHOÁ TÀI KHOẢN
    // ===========================

    /**
     * Ghi dấu tài khoản bị khoá. TTL bằng thời gian sống của access token: sau đó mọi token cũ đã tự hết hạn nên không
     * cần giữ dấu nữa (đăng nhập và refresh đã chặn bằng status trong DB).
     */
    public void markUserLocked(UUID userId, Duration ttl) {
        redisTemplate.opsForValue().set(buildLockKey(userId), "1", ttl.toMillis(), TimeUnit.MILLISECONDS);
        log.debug("Marked user {} as locked for {}s", userId, ttl.getSeconds());
    }

    /** Gỡ dấu khoá (mở khoá tài khoản). */
    public void clearUserLocked(UUID userId) {
        redisTemplate.delete(buildLockKey(userId));
    }

    /** Tài khoản có đang bị admin khoá không (một lệnh Redis O(1), không query DB). */
    public boolean isUserLocked(UUID userId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(buildLockKey(userId)));
    }

    // ===========================
    //  PRIVATE HELPERS
    // ===========================

    private String buildLockKey(UUID userId) {
        return AppConstant.REDIS_USER_LOCK_PREFIX + userId;
    }

    private String buildRefreshTokenKey(UUID userId, String sessionId) {
        return AppConstant.REDIS_REFRESH_TOKEN_PREFIX + userId.toString() + ":" + sessionId;
    }

    private String buildBlacklistKey(String jti) {
        return AppConstant.REDIS_ACCESS_BLACKLIST_PREFIX + jti;
    }
}
