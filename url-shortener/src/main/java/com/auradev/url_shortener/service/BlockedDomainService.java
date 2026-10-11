package com.auradev.url_shortener.service;

import com.auradev.url_shortener.dto.request.AddBlockedDomainRequest;
import com.auradev.url_shortener.dto.response.BlockedDomainResponse;
import com.auradev.url_shortener.dto.response.PageResponse;
import org.springframework.data.domain.Pageable;

/**
 * Quản lý blocklist domain (admin).
 */
public interface BlockedDomainService {

    /**
     * Thêm domain vào blocklist. Nếu {@code blockExistingLinks} thì các link hiện có trỏ tới domain đó
     * (ACTIVE/DISABLED/EXPIRED) bị chặn luôn bằng một câu SQL.
     */
    BlockedDomainResponse add(AddBlockedDomainRequest request);

    PageResponse<BlockedDomainResponse> list(String keyword, Pageable pageable);

    /**
     * Xoá domain khỏi blocklist. Các link đã bị chặn trước đó <b>không</b> tự gỡ chặn
     * (không biết trạng thái cũ của chúng): admin gỡ từng link nếu cần.
     */
    void remove(Long id);
}
