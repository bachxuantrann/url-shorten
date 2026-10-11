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
 *     unlock-per-ip:          { limit: 30,  window: PT15M }  # theo IP, mọi link
 *     unlock-per-link-and-ip: { limit: 10,  window: PT15M }  # theo (link, IP), thử mật khẩu link
 *     unlock-per-link:        { limit: 100, window: PT1H }   # theo link, mọi IP
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

    /**
     * Thử mở khoá link có mật khẩu: tổng theo IP trên mọi link. Chặn quét nhiều link và giữ số
     * khoá Redis tạo ra bị giới hạn theo số IP.
     */
    private Rule unlockPerIp = new Rule(30, Duration.ofMinutes(15));

    /** Thử mở khoá link có mật khẩu: theo cặp (link, IP), chặn đoán mật khẩu từ một nguồn. */
    private Rule unlockPerLinkAndIp = new Rule(10, Duration.ofMinutes(15));

    /** Thử mở khoá link có mật khẩu: tổng theo link, chặn đoán mật khẩu phân tán từ nhiều IP. */
    private Rule unlockPerLink = new Rule(100, Duration.ofHours(1));

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
