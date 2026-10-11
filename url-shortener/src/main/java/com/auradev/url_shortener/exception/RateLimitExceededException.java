package com.auradev.url_shortener.exception;

import lombok.Getter;

/**
 * Vượt giới hạn tốc độ. {@code GlobalExceptionHandler} trả HTTP 429 kèm header {@code Retry-After}.
 */
@Getter
public class RateLimitExceededException extends AppException {

    private final long retryAfterSeconds;

    public RateLimitExceededException(long retryAfterSeconds) {
        // Truyền String để MessageFormat không chèn dấu phân cách hàng nghìn
        super(ErrorCode.RATE_LIMITED, String.valueOf(retryAfterSeconds));
        this.retryAfterSeconds = retryAfterSeconds;
    }
}
