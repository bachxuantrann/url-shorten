package com.auradev.url_shortener.service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Chuyển các link ACTIVE đã quá {@code expiresAt} sang EXPIRED.
 */
public interface UrlExpirationService {

    /**
     * Xử lý tối đa {@code batchSize} link quá hạn trong <b>một transaction</b>.
     * An toàn khi nhiều instance gọi đồng thời: mỗi link chỉ được một bên xử lý.
     *
     * @param now thời điểm dùng để so sánh với {@code expiresAt}
     * @return short code của các link vừa được chuyển sang EXPIRED (rỗng nếu không còn link nào)
     */
    List<String> expireBatch(LocalDateTime now, int batchSize);
}
