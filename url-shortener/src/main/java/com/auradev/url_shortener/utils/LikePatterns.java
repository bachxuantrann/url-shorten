package com.auradev.url_shortener.utils;

import java.util.Locale;

/**
 * Dựng mẫu LIKE từ từ khoá người dùng nhập.
 */
public final class LikePatterns {

    private LikePatterns() {
    }

    /**
     * Chuyển từ khoá thành mẫu LIKE an toàn: hạ chữ thường, escape {@code !}, {@code %}, {@code _}
     * (ký tự escape khai báo trong query là {@code !}) để người dùng không tự chèn wildcard.
     * Từ khoá rỗng cho ra {@code "%"} (khớp tất cả).
     */
    public static String contains(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return "%";
        }
        String escaped = keyword.trim().toLowerCase(Locale.ROOT)
                .replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_");
        return "%" + escaped + "%";
    }
}
