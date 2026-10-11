package com.auradev.url_shortener.service;

import com.auradev.url_shortener.dto.response.AdminUrlResponse;
import com.auradev.url_shortener.dto.response.PageResponse;
import com.auradev.url_shortener.enums.UrlStatusEnum;
import org.springframework.data.domain.Pageable;

/**
 * Thao tác của admin trên link của bất kỳ ai (kể cả link ẩn danh).
 */
public interface AdminUrlService {

    /**
     * Danh sách link của mọi người dùng (không gồm link đã xoá), lọc theo trạng thái và từ khoá
     * (URL đích, short code hoặc người tạo). Link ACTIVE đã quá hạn được báo là EXPIRED như API redirect.
     */
    PageResponse<AdminUrlResponse> list(String keyword, UrlStatusEnum status, Pageable pageable);

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
