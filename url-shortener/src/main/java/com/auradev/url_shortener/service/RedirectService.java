package com.auradev.url_shortener.service;

/**
 * Nghiệp vụ redirect công khai: từ short code tìm ra URL đích hoặc báo lý do không redirect được.
 */
public interface RedirectService {

    /**
     * @param shortCode mã trong đường dẫn {@code /{shortCode}}
     * @return URL đích để redirect
     * @throws com.auradev.url_shortener.exception.AppException
     *         {@code URL_NOT_FOUND} (không tồn tại / đang tắt / mã không hợp lệ hoặc dành riêng),
     *         {@code URL_GONE} (hết hạn hoặc đã xoá),
     *         {@code URL_ACCESS_BLOCKED} (bị chặn)
     */
    String resolveTarget(String shortCode);
}
