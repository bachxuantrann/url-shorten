package com.auradev.url_shortener.service;

import com.auradev.url_shortener.dto.response.AdminUrlResponse;

/**
 * Thao tác của admin trên link của bất kỳ ai (kể cả link ẩn danh).
 */
public interface AdminUrlService {

    /** Xem một link (kể cả khi đang BLOCKED/DISABLED). Link đã xoá coi như không tồn tại. */
    AdminUrlResponse getUrl(String shortCode);

    /**
     * Chặn link: redirect trả 403 và chủ link không sửa/tắt/xoá được. Gọi lại trên link đã bị chặn
     * sẽ cập nhật lý do.
     */
    AdminUrlResponse blockUrl(String shortCode, String reason);

    /** Gỡ chặn: BLOCKED → ACTIVE. Link ở trạng thái khác trả {@code URL_INVALID_STATE}. */
    AdminUrlResponse unblockUrl(String shortCode);
}
