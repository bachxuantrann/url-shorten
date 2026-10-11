package com.auradev.url_shortener.utils;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Lấy IP của client.
 *
 * <p>Chỉ dùng {@code getRemoteAddr()}. Khi chạy sau reverse proxy, Tomcat
 * ({@code server.forward-headers-strategy=native}) tự thay giá trị này bằng {@code X-Forwarded-For}
 * <b>chỉ khi kết nối đến từ proxy tin cậy</b> (dải IP riêng/loopback mặc định). Vì vậy client từ
 * internet không giả mạo được IP bằng cách tự gửi header {@code X-Forwarded-For}, và không được tự đọc
 * header đó ở đây.
 */
public final class ClientIpUtils {

    private ClientIpUtils() {
    }

    public static String resolve(HttpServletRequest request) {
        String address = request.getRemoteAddr();
        return address == null || address.isBlank() ? "unknown" : address.trim();
    }
}
