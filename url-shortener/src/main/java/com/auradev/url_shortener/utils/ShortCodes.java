package com.auradev.url_shortener.utils;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Quy ước về định dạng short code và danh sách mã dành riêng.
 *
 * <p>Short code nằm ngay sau domain ({@code https://sho.rt/{code}}) nên không được trùng
 * với các đường dẫn của chính hệ thống ({@code /api}, {@code /actuator}...).
 */
public final class ShortCodes {

    public static final int MIN_LENGTH = 3;
    public static final int MAX_LENGTH = 32;

    private static final Pattern CODE_CHARS = Pattern.compile("[0-9a-zA-Z_-]+");

    /**
     * Mã dành riêng (so sánh không phân biệt hoa thường). Không được cấp cho link nào
     * và không bao giờ được redirect.
     */
    private static final Set<String> RESERVED = Set.of(
            "api", "actuator", "swagger-ui", "swagger", "v3", "error", "admin", "health",
            "static", "assets", "favicon", "robots", "login", "logout", "register",
            "auth", "users", "urls", "docs", "console", "internal", "public"
    );

    private ShortCodes() {
    }

    public static boolean isReserved(String code) {
        return code != null && RESERVED.contains(code.toLowerCase(Locale.ROOT));
    }

    /** Đúng ký tự cho phép và độ dài {@value #MIN_LENGTH}-{@value #MAX_LENGTH}. */
    public static boolean isValidFormat(String code) {
        return code != null
                && code.length() >= MIN_LENGTH
                && code.length() <= MAX_LENGTH
                && CODE_CHARS.matcher(code).matches();
    }

    /**
     * Đường dẫn có dạng {@code /{segment}} (một đoạn duy nhất, chỉ gồm ký tự hợp lệ của mã)
     * và không phải mã dành riêng. Dùng để mở công khai route redirect trong Spring Security:
     * độ dài được kiểm tra riêng ở controller để trả 404 thay vì 401.
     */
    public static boolean isRedirectPath(String path) {
        if (path == null || path.length() < 2 || path.charAt(0) != '/') {
            return false;
        }
        String segment = path.substring(1);
        return CODE_CHARS.matcher(segment).matches() && !isReserved(segment);
    }
}
