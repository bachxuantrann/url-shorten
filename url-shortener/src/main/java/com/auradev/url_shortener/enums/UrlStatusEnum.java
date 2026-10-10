package com.auradev.url_shortener.enums;

/**
 * Trạng thái nghiệp vụ của một short link.
 * <ul>
 *   <li>ACTIVE   – Đang hoạt động, redirect bình thường</li>
 *   <li>DISABLED – Chủ link tạm tắt, có thể bật lại</li>
 *   <li>EXPIRED  – Đã hết hạn (xem {@code expires_at}), sửa hạn mới để bật lại</li>
 *   <li>BLOCKED  – Bị hệ thống/admin chặn (lạm dụng), chủ link không tự gỡ được</li>
 *   <li>DELETED  – Xoá mềm, không hiển thị và không bao giờ tái sử dụng mã</li>
 * </ul>
 */
public enum UrlStatusEnum {
    ACTIVE,
    DISABLED,
    EXPIRED,
    BLOCKED,
    DELETED
}
