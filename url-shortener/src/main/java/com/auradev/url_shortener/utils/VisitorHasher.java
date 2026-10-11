package com.auradev.url_shortener.utils;

import com.auradev.url_shortener.config.ClickProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.LocalDate;
import java.util.HexFormat;

/**
 * Băm danh tính khách truy cập để đếm unique visitor mà không lưu IP thô.
 *
 * <p>{@code HMAC-SHA256(secret, ngày | IP | User-Agent)} cắt còn 32 ký tự hex. Có khoá nên không thể
 * dò ngược IPv4 bằng cách thử hết 2^32 giá trị; có ngày trong dữ liệu băm nên cùng một người ở hai
 * ngày khác nhau cho hai giá trị khác nhau (không theo dõi chéo ngày). Hệ quả: "unique visitor" chỉ
 * có nghĩa trong phạm vi một ngày.
 */
@Component
public class VisitorHasher {

    private static final String ALGORITHM = "HmacSHA256";
    private static final int HEX_LENGTH = 32;

    private final SecretKeySpec key;

    public VisitorHasher(ClickProperties properties) {
        String secret = properties.getVisitorSecret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("app.click.visitor-secret must be set (env CLICK_VISITOR_SECRET)");
        }
        this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM);
    }

    public String hash(String ip, String userAgent, LocalDate day) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            String data = day + "|" + (ip == null ? "" : ip) + "|" + (userAgent == null ? "" : userAgent);
            byte[] digest = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, HEX_LENGTH);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }
}
