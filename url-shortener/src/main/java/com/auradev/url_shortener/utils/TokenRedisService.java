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
 *   <li>{@code RT:{userId}}     → refresh token string, TTL = refreshTokenExpiry</li>
 *   <li>{@code BL:{jti}}        → "1" (revoked marker), TTL = thời gian còn lại của access token</li>
 *   <li>{@code REVOKE:{userId}} → epochMs của lần logoutAllDevices, TTL = access token max lifetime</li>
 * </ul>
 *
 * <p>Thiết kế single-session: {@code RT:{userId}} lưu một refresh token duy nhất.
 * Login mới sẽ ghi đè RT cũ → chỉ session mới nhất có thể refresh.
 * Để hỗ trợ multi-device, mở rộng key thành {@code RT:{userId}:{deviceId}}.
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
     * @param refreshToken chuỗi refresh token
     * @param ttl          thời gian sống
     */
    public void saveRefreshToken(UUID userId, String refreshToken, Duration ttl) {
        String key = buildRefreshTokenKey(userId);
        redisTemplate.opsForValue().set(key, refreshToken, ttl.toMillis(), TimeUnit.MILLISECONDS);
        log.debug("Saved refresh token for user {} with TTL {}s", userId, ttl.getSeconds());
    }

    /**
     * Lấy refresh token đang active của user.
     *
     * @return refresh token string, hoặc {@code null} nếu đã hết hạn / không tồn tại
     */
    public String getRefreshToken(UUID userId) {
        return redisTemplate.opsForValue().get(buildRefreshTokenKey(userId));
    }

    /**
     * Kiểm tra refresh token của user có hợp lệ không.
     * So sánh exact với token đang lưu trong Redis.
     */
    public boolean isRefreshTokenValid(UUID userId, String refreshToken) {
        String stored = getRefreshToken(userId);
        return stored != null && stored.equals(refreshToken);
    }

    /**
     * Xoá refresh token của user (logout hoặc rotate token).
     */
    public void deleteRefreshToken(UUID userId) {
        Boolean deleted = redisTemplate.delete(buildRefreshTokenKey(userId));
        log.debug("Deleted refresh token for user {}: {}", userId, deleted);
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
    //  GLOBAL REVOKE (LOGOUT ALL)
    // ===========================

    /**
     * Lưu timestamp revoke toàn bộ thiết bị của user (epoch milliseconds).
     *
     * <p>Bất kỳ access token nào có {@code issuedAt ≤ revokedAt} đều sẽ bị reject
     * ngay tại {@link com.auradev.url_shortener.security.JwtAuthenticationFilter},
     * không cần phải blacklist từng token riêng lẻ.
     *
     * <p>TTL = max access token lifetime: sau khoảng này không còn AT nào
     * được issued trước timestamp còn valid → Redis tự clean.
     *
     * @param userId            ID của user
     * @param accessTokenMaxTtl TTL = thời gian sống tối đa của một access token
     */
    public void setGlobalRevokeTimestamp(UUID userId, Duration accessTokenMaxTtl) {
        String key = AppConstant.REDIS_REVOKE_PREFIX + userId;
        redisTemplate.opsForValue().set(
                key,
                String.valueOf(System.currentTimeMillis()),
                accessTokenMaxTtl.toMillis(),
                TimeUnit.MILLISECONDS
        );
        log.debug("Set global revoke timestamp for user {} (TTL={}s)", userId, accessTokenMaxTtl.getSeconds());
    }

    /**
     * Lấy global revoke timestamp của user.
     *
     * @return epoch milliseconds, hoặc {@code null} nếu không có (chưa logoutAllDevices)
     */
    public Long getGlobalRevokeTimestamp(UUID userId) {
        String val = redisTemplate.opsForValue().get(AppConstant.REDIS_REVOKE_PREFIX + userId);
        return val != null ? Long.parseLong(val) : null;
    }

    // ===========================
    //  PRIVATE HELPERS
    // ===========================

    private String buildRefreshTokenKey(UUID userId) {
        return AppConstant.REDIS_REFRESH_TOKEN_PREFIX + userId.toString();
    }

    private String buildBlacklistKey(String jti) {
        return AppConstant.REDIS_ACCESS_BLACKLIST_PREFIX + jti;
    }
}
