package com.auradev.url_shortener.utils;

import com.auradev.url_shortener.exception.AppException;
import com.auradev.url_shortener.exception.ErrorCode;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * Kiểm tra cơ bản URL đích do người dùng nhập.
 *
 * <p>Chỉ kiểm tra định dạng: scheme {@code http}/{@code https}, có host, không có
 * userinfo ({@code user@host} hay bị dùng để giả mạo domain), độ dài hợp lệ.
 * Các luật chống lạm dụng (chặn localhost, IP nội bộ, domain độc hại) thuộc phase sau.
 */
public final class UrlUtils {

    public static final int MAX_URL_LENGTH = 2048;

    private UrlUtils() {
    }

    /**
     * @return URL đã được trim
     * @throws AppException {@link ErrorCode#INVALID_URL} nếu URL không hợp lệ
     */
    public static String validate(String raw) {
        String url = raw == null ? "" : raw.trim();
        if (url.isEmpty() || url.length() > MAX_URL_LENGTH) {
            throw new AppException(ErrorCode.INVALID_URL);
        }

        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new AppException(ErrorCode.INVALID_URL);
        }

        String scheme = uri.getScheme();
        boolean httpScheme = "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
        String host = uri.getHost();

        if (!httpScheme || host == null || host.isBlank() || uri.getRawUserInfo() != null) {
            throw new AppException(ErrorCode.INVALID_URL);
        }
        return url;
    }
}
