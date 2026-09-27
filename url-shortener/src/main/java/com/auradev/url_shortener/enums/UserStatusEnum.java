package com.auradev.url_shortener.enums;

/**
 * Trạng thái tài khoản người dùng.
 * <ul>
 *   <li>ACTIVE   – Tài khoản đang hoạt động bình thường</li>
 *   <li>INACTIVE – Tài khoản chưa kích hoạt (ví dụ: chưa xác minh email)</li>
 *   <li>BANNED   – Tài khoản bị khoá do vi phạm</li>
 * </ul>
 */
public enum UserStatusEnum {
    ACTIVE,
    INACTIVE,
    BANNED
}
