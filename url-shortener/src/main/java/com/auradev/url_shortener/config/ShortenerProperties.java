package com.auradev.url_shortener.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

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

    /**
     * Thời gian tối đa một link được cache trong Redis. Cũng là mức "cũ" tối đa nếu một lần
     * xoá cache bị lỡ (Redis lỗi lúc xoá). Mặc định 10 phút.
     */
    private Duration cacheTtl = Duration.ofMinutes(10);

    /**
     * Thời gian cache kết quả "không tồn tại" (và link đã hết hạn) để tránh dò mã làm
     * quá tải DB. Mặc định 1 phút.
     */
    private Duration negativeCacheTtl = Duration.ofMinutes(1);
}
