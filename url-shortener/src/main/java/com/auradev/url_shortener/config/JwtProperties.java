package com.auradev.url_shortener.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Cấu hình JWT được bind từ {@code application.yaml} (prefix: {@code app.jwt}).
 *
 * <p>Ví dụ config:
 * <pre>
 * app:
 *   jwt:
 *     secret: your-256-bit-base64-secret
 *     access-token-expiry: 900000    # 15 phút
 *     refresh-token-expiry: 604800000 # 7 ngày
 *     issuer: url-shortener
 * </pre>
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.jwt")
public class JwtProperties {

    /**
     * Secret key dạng hex hoặc base64 (ít nhất 256-bit / 32 bytes).
     * <br>Luôn đưa vào environment variable ở môi trường production.
     */
    private String secret;

    /**
     * Thời gian sống của Access Token (ms).
     * Mặc định: 900_000 ms = 15 phút.
     */
    private long accessTokenExpiry = 900_000L;

    /**
     * Thời gian sống của Refresh Token (ms).
     * Mặc định: 604_800_000 ms = 7 ngày.
     */
    private long refreshTokenExpiry = 604_800_000L;

    /**
     * Issuer claim (iss) của JWT.
     */
    private String issuer = "url-shortener";
}
