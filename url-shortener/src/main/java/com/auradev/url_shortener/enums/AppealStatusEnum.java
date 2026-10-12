package com.auradev.url_shortener.enums;

/**
 * Trạng thái của một kháng nghị link bị chặn.
 */
public enum AppealStatusEnum {
    /** Chờ admin xem xét */
    OPEN,
    /** Admin chấp nhận: link đã được gỡ chặn */
    ACCEPTED,
    /** Admin từ chối (kèm lời phản hồi nếu có) */
    REJECTED
}
