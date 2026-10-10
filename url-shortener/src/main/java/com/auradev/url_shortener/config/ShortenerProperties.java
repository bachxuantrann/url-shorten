package com.auradev.url_shortener.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Cấu hình nghiệp vụ rút gọn link, bind từ {@code application.yaml} (prefix: {@code app.shortener}).
 *
 * <pre>
 * app:
 *   shortener:
 *     base-url: https://sho.rt
 *     code-secret: chuoi-bi-mat-dai
 * </pre>
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.shortener")
public class ShortenerProperties {

    /**
     * Domain công khai của short link, dùng để dựng {@code shortUrl} trong response.
     * Không có dấu {@code /} ở cuối (nếu có sẽ bị cắt).
     */
    private String baseUrl = "http://localhost:8080";

    /**
     * Khoá bí mật để xáo trộn short code (xem {@code ShortCodeGenerator}).
     * <br>Phải cố định qua các lần deploy: đổi khoá sẽ làm các mã sinh ra sau đó
     * có thể trùng với mã đã cấp trước đó. Luôn đưa vào environment variable ở production.
     */
    private String codeSecret;
}
