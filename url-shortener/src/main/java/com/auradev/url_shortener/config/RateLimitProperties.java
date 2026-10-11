package com.auradev.url_shortener.config;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Cấu hình giới hạn tốc độ, bind từ {@code application.yaml} (prefix: {@code app.rate-limit}).
 *
 * <pre>
 * app:
 *   rate-limit:
 *     anonymous-create: { limit: 10,  window: PT1H }   # theo IP, tạo link ẩn danh
 *     user-create:      { limit: 100, window: PT1H }   # theo user, tạo link khi đã đăng nhập
 * </pre>
 *
 * <p>{@code limit <= 0} nghĩa là tắt luật đó.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.rate-limit")
public class RateLimitProperties {

    private Rule anonymousCreate = new Rule(10, Duration.ofHours(1));

    private Rule userCreate = new Rule(100, Duration.ofHours(1));

    /** Tối đa {@code limit} request trong mỗi cửa sổ {@code window} (cửa sổ cố định). */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Rule {
        private int limit;
        private Duration window;
    }
}
