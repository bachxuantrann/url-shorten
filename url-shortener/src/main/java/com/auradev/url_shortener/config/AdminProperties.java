package com.auradev.url_shortener.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Cấu hình tài khoản Admin mặc định được bind từ {@code application.yaml}
 * (prefix: {@code app.admin}).
 *
 * <p>Giá trị được truyền vào yaml qua env vars:
 * <pre>
 * app:
 *   admin:
 *     username: ${ADMIN_USERNAME}
 *     password: ${ADMIN_PASSWORD}   # raw — BCrypt encode trong DataInitializer
 *     email:    ${ADMIN_EMAIL}
 *     full-name: ${ADMIN_FULL_NAME:System Admin}
 * </pre>
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.admin")
public class AdminProperties {

    /** Username đăng nhập của tài khoản admin mặc định. */
    private String username;

    /**
     * Mật khẩu raw của admin — sẽ được BCrypt encode trong {@link DataInitializer}.
     * Không bao giờ lưu dạng plain text vào DB.
     */
    private String password;

    /** Email của tài khoản admin mặc định. */
    private String email;

    /** Họ tên hiển thị. Mặc định: {@code "System Admin"}. */
    private String fullName = "System Admin";
}
