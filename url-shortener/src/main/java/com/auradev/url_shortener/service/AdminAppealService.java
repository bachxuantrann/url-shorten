package com.auradev.url_shortener.service;

import com.auradev.url_shortener.dto.response.AdminAppealResponse;
import com.auradev.url_shortener.dto.response.PageResponse;
import com.auradev.url_shortener.enums.AppealStatusEnum;
import org.springframework.data.domain.Pageable;

/**
 * Admin xem xét kháng nghị của chủ link.
 */
public interface AdminAppealService {

    /** Kháng nghị theo trạng thái: đang chờ thì cũ nhất trước, đã xử lý thì xử lý gần nhất trước. */
    PageResponse<AdminAppealResponse> list(AppealStatusEnum status, Pageable pageable);

    /**
     * Chấp nhận: gỡ chặn link (như {@code AdminUrlService.unblockUrl}, kể cả xoá cache redirect) và đánh dấu
     * kháng nghị ACCEPTED, trong một transaction. Kháng nghị không còn chờ trả {@code APPEAL_NOT_OPEN}.
     */
    AdminAppealResponse accept(long appealId);

    /** Từ chối, kèm lời phản hồi tuỳ chọn cho chủ link. Link vẫn bị chặn. */
    AdminAppealResponse reject(long appealId, String response);
}
