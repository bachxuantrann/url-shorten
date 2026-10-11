package com.auradev.url_shortener.utils;

import com.auradev.url_shortener.config.RateLimitProperties.Rule;
import com.auradev.url_shortener.exception.RateLimitExceededException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Giới hạn tốc độ bằng Redis, thuật toán cửa sổ cố định: mỗi {@code (bucket, subject)} có một bộ
 * đếm {@code RL:{bucket}:{subject}} tăng theo mỗi request và tự hết hạn sau {@code window}.
 *
 * <p>{@code INCR} và {@code EXPIRE} chạy trong <b>một Lua script</b> (nguyên tử): không thể có key
 * không có TTL nếu tiến trình chết giữa hai lệnh, và đúng một round-trip cho mỗi request.
 *
 * <p>Hạn chế đã biết: cửa sổ cố định cho phép tối đa {@code 2 × limit} request quanh ranh giới hai
 * cửa sổ. Chấp nhận được cho mục đích chống spam tạo link.
 *
 * <p><b>Fail-open:</b> nếu Redis không dùng được, request vẫn được cho qua (và ghi log cảnh báo) để một
 * sự cố Redis không làm sập chức năng tạo link, cùng triết lý với cache redirect. Circuit breaker
 * {@code redisRateLimit} cắt các lần chờ timeout liên tiếp: khi mở, giới hạn bị bỏ qua ngay.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimitService {

    private static final String KEY_PREFIX = "RL:";

    @SuppressWarnings("rawtypes")
    private static final DefaultRedisScript<List> INCREMENT_SCRIPT = new DefaultRedisScript<>(
            """
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then
                redis.call('EXPIRE', KEYS[1], ARGV[1])
            end
            local ttl = redis.call('TTL', KEYS[1])
            if ttl < 0 then
                redis.call('EXPIRE', KEYS[1], ARGV[1])
                ttl = tonumber(ARGV[1])
            end
            return {count, ttl}
            """,
            List.class);

    private final RedisTemplate<String, String> redisTemplate;
    private final CircuitBreaker                redisRateLimitBreaker;

    /**
     * Ghi nhận một request và kiểm tra giới hạn.
     *
     * @param bucket  tên nhóm giới hạn, ví dụ {@code anon-create}
     * @param subject đối tượng bị giới hạn: IP hoặc userId
     * @throws RateLimitExceededException khi vượt {@code rule.limit} trong cửa sổ hiện tại
     */
    public void check(String bucket, String subject, Rule rule) {
        if (rule == null || rule.getLimit() <= 0) {
            return; // luật bị tắt
        }

        long windowSeconds = Math.max(1, rule.getWindow().toSeconds());
        List<?> result;
        try {
            result = redisRateLimitBreaker.executeSupplier(() -> redisTemplate.execute(
                    INCREMENT_SCRIPT, List.of(KEY_PREFIX + bucket + ":" + subject), String.valueOf(windowSeconds)));
        } catch (CallNotPermittedException e) {
            log.debug("Redis circuit open, rate limit skipped (bucket={})", bucket);
            return;
        } catch (RuntimeException e) {
            log.warn("Redis unavailable, rate limit skipped (bucket={}): {}", bucket, e.toString());
            return;
        }
        if (result == null || result.size() < 2) {
            return;
        }

        long count = ((Number) result.get(0)).longValue();
        long ttl   = ((Number) result.get(1)).longValue();
        if (count > rule.getLimit()) {
            log.warn("Rate limit exceeded: bucket={}, subject={}, count={}, limit={}", bucket, subject, count, rule.getLimit());
            throw new RateLimitExceededException(ttl > 0 ? ttl : windowSeconds);
        }
    }
}
