package com.auradev.url_shortener.utils;

import com.auradev.url_shortener.config.ShortenerProperties;
import com.auradev.url_shortener.constant.AppConstant;
import com.auradev.url_shortener.entity.Url;
import com.auradev.url_shortener.enums.UrlStatusEnum;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/**
 * Cache Redis cho đường redirect (cache-aside).
 *
 * <p>Key {@code URL:{shortCode}}, giá trị dạng chuỗi nhỏ gọn {@code STATUS|expiresAt|originalUrl}
 * ({@code expiresAt} là {@code -} nếu không có hạn) hoặc {@code MISSING} khi mã không tồn tại
 * (negative cache). URL đứng cuối nên được phép chứa ký tự {@code |}.
 *
 * <p><b>Redis lỗi không bao giờ làm hỏng redirect:</b> mọi thao tác bắt exception, ghi log và
 * coi như cache miss để caller đọc thẳng DB.
 *
 * <p><b>Xoá cache:</b> mọi thay đổi trạng thái/URL/hạn của link phải gọi {@link #evictAfterCommit}
 * (xoá <i>sau khi</i> transaction commit để reader không nạp lại dữ liệu cũ giữa lúc xoá và commit).
 * Vẫn còn một cửa sổ race rất hẹp (reader đọc DB trước commit rồi ghi cache sau lần xoá),
 * mức cũ tối đa bị chặn bởi {@code app.shortener.cache-ttl}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UrlCacheService {

    /** Thông tin tối thiểu để quyết định redirect. */
    public record CachedUrl(UrlStatusEnum status, LocalDateTime expiresAt, String originalUrl) {
        public boolean isMissing() {
            return status == null;
        }
    }

    /** Kết quả "mã không tồn tại" đã được cache. */
    public static final CachedUrl MISSING = new CachedUrl(null, null, null);

    private static final String MISSING_MARKER = "MISSING";
    private static final String NO_EXPIRY = "-";

    private final RedisTemplate<String, String> redisTemplate;
    private final ShortenerProperties           properties;

    /**
     * @return giá trị trong cache; rỗng nếu cache miss, dữ liệu hỏng hoặc Redis không dùng được
     */
    public Optional<CachedUrl> get(String code) {
        try {
            String raw = redisTemplate.opsForValue().get(key(code));
            if (raw == null) {
                return Optional.empty();
            }
            CachedUrl cached = deserialize(raw);
            if (cached == null) {
                log.warn("Corrupted URL cache entry for code={}, ignoring", code);
            }
            return Optional.ofNullable(cached);
        } catch (RuntimeException e) {
            log.warn("Redis unavailable while reading URL cache (code={}): {}", code, e.toString());
            return Optional.empty();
        }
    }

    public void put(String code, Url url) {
        try {
            String value = url.getStatus().name() + '|'
                    + (url.getExpiresAt() == null ? NO_EXPIRY : url.getExpiresAt().toString()) + '|'
                    + url.getOriginalUrl();
            redisTemplate.opsForValue().set(key(code), value, ttlFor(url));
        } catch (RuntimeException e) {
            log.warn("Redis unavailable while writing URL cache (code={}): {}", code, e.toString());
        }
    }

    /** Ghi nhận mã không tồn tại trong thời gian ngắn. */
    public void putMissing(String code) {
        try {
            redisTemplate.opsForValue().set(key(code), MISSING_MARKER, properties.getNegativeCacheTtl());
        } catch (RuntimeException e) {
            log.warn("Redis unavailable while writing negative URL cache (code={}): {}", code, e.toString());
        }
    }

    public void evict(String code) {
        try {
            redisTemplate.delete(key(code));
        } catch (RuntimeException e) {
            // Cache có thể còn dữ liệu cũ đến hết TTL
            log.error("Could not evict URL cache for code={}, stale until TTL: {}", code, e.toString());
        }
    }

    /**
     * Xoá cache sau khi transaction hiện tại commit (hoặc ngay lập tức nếu không có transaction).
     */
    public void evictAfterCommit(String code) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    evict(code);
                }
            });
        } else {
            evict(code);
        }
    }

    // ===========================
    //  PRIVATE HELPERS
    // ===========================

    /**
     * TTL = min(cache-ttl, thời gian còn lại đến lúc hết hạn). Link đã hết hạn chỉ cache ngắn
     * (negative-cache-ttl) vì kết quả luôn là 410 cho đến khi chủ link đổi hạn (lúc đó cache bị xoá).
     */
    private Duration ttlFor(Url url) {
        Duration ttl = properties.getCacheTtl();
        if (url.getExpiresAt() != null) {
            Duration remaining = Duration.between(LocalDateTime.now(), url.getExpiresAt());
            if (remaining.isNegative() || remaining.isZero()) {
                return properties.getNegativeCacheTtl();
            }
            if (remaining.compareTo(ttl) < 0) {
                ttl = remaining;
            }
        }
        return ttl;
    }

    private CachedUrl deserialize(String raw) {
        if (MISSING_MARKER.equals(raw)) {
            return MISSING;
        }
        String[] parts = raw.split("\\|", 3);
        if (parts.length != 3) {
            return null;
        }
        try {
            UrlStatusEnum status = UrlStatusEnum.valueOf(parts[0]);
            LocalDateTime expiresAt = NO_EXPIRY.equals(parts[1]) ? null : LocalDateTime.parse(parts[1]);
            return new CachedUrl(status, expiresAt, parts[2]);
        } catch (IllegalArgumentException | DateTimeParseException e) {
            return null;
        }
    }

    private String key(String code) {
        return AppConstant.REDIS_URL_CACHE_PREFIX + code;
    }
}
