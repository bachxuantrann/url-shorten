package com.auradev.url_shortener.service;

/**
 * Nghiệp vụ redirect công khai: từ short code tìm ra URL đích hoặc báo lý do không redirect được.
 */
public interface RedirectService {

    /**
     * Đường dẫn công khai {@code GET /{shortCode}}.
     *
     * @param shortCode mã trong đường dẫn {@code /{shortCode}}
     * @return URL đích để redirect
     * @throws com.auradev.url_shortener.exception.AppException
     *         {@code URL_NOT_FOUND} (không tồn tại / đang tắt / mã không hợp lệ hoặc dành riêng),
     *         {@code URL_GONE} (hết hạn hoặc đã xoá),
     *         {@code URL_ACCESS_BLOCKED} (bị chặn),
     *         {@code URL_PASSWORD_REQUIRED} (link có mật khẩu: phải gọi {@link #unlockTarget} thay thế)
     */
    String resolveTarget(String shortCode);

    /**
     * Mở một link được bảo vệ bằng mật khẩu. Áp dụng đúng các quy tắc trạng thái/hết hạn của
     * {@link #resolveTarget} <b>trước</b> khi kiểm tra mật khẩu (link đã tắt, hết hạn hay bị chặn
     * không tốn CPU băm mật khẩu). Link không có mật khẩu trả thẳng URL đích.
     *
     * <p>Luôn đọc DB (không đọc cache): URL đích của link có mật khẩu không được lưu trong cache.
     * Việc giới hạn số lần thử thuộc tầng controller (cần IP).
     *
     * @return URL đích khi mật khẩu đúng
     * @throws com.auradev.url_shortener.exception.AppException
     *         như {@link #resolveTarget} và {@code URL_PASSWORD_INCORRECT} khi sai mật khẩu
     */
    String unlockTarget(String shortCode, String password);
}
