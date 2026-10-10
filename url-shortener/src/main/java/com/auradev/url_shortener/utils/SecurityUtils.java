package com.auradev.url_shortener.utils;

import jakarta.servlet.http.HttpServletRequest;

import java.util.UUID;

/**
 * Tiện ích lấy thông tin người dùng đang đăng nhập từ request.
 */
public final class SecurityUtils {

    /** Tên request attribute do {@code JwtAuthenticationFilter} set */
    public static final String AUTHENTICATED_USER_ID_ATTRIBUTE = "authenticatedUserId";

    private SecurityUtils() {
    }

    /**
     * Lấy userId đã được {@code JwtAuthenticationFilter} gắn vào request.
     *
     * @throws IllegalStateException nếu request chưa được xác thực
     */
    public static UUID getAuthenticatedUserId(HttpServletRequest request) {
        Object userId = request.getAttribute(AUTHENTICATED_USER_ID_ATTRIBUTE);
        if (userId instanceof UUID uuid) {
            return uuid;
        }
        throw new IllegalStateException("User ID not found in request context");
    }
}
