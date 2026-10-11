package com.auradev.url_shortener.enums;

/**
 * Trạng thái xử lý của một báo cáo lạm dụng.
 */
public enum AbuseReportStatusEnum {
    /** Chờ admin xem xét */
    OPEN,
    /** Admin đã xử lý (ví dụ đã chặn link) */
    RESOLVED,
    /** Admin xác định không phải lạm dụng */
    DISMISSED
}
