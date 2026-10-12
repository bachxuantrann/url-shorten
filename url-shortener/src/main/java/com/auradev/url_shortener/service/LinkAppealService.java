package com.auradev.url_shortener.service;

import com.auradev.url_shortener.dto.response.AppealInfoResponse;
import com.auradev.url_shortener.entity.Url;
import com.auradev.url_shortener.enums.AppealStatusEnum;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Kháng nghị của chủ link bị chặn (phía chủ link). Phía admin xem {@link AdminAppealService}.
 */
public interface LinkAppealService {

    /**
     * Gửi kháng nghị cho link của {@code userId}. Link không phải của user hoặc đã xoá trả {@code URL_NOT_FOUND};
     * link không bị chặn trả {@code URL_INVALID_STATE}; link đã có kháng nghị đang chờ trả
     * {@code APPEAL_ALREADY_OPEN}. Sau khi kháng nghị được xử lý thì được gửi cái mới.
     */
    void submit(UUID userId, String shortCode, String message);

    /**
     * Kháng nghị hiện tại của một lô link bị chặn (key = id link), bằng một câu truy vấn. Link không có kháng nghị
     * liên quan đến lần chặn hiện tại thì không có trong map. Đưa vào link không bị chặn là lỗi của người gọi.
     */
    Map<Long, AppealInfoResponse> currentFor(Collection<Url> blockedUrls);

    /** Đóng kháng nghị đang chờ của link (nếu có) khi admin gỡ chặn trực tiếp; trả về số kháng nghị đã đóng. */
    int closeForUrl(long urlId, AppealStatusEnum newStatus);
}
