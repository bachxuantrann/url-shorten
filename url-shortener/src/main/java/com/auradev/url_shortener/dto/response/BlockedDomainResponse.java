package com.auradev.url_shortener.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Một domain trong blocklist.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BlockedDomainResponse {

    private Long id;
    private String domain;
    private String reason;
    private LocalDateTime createdAt;
    private String createdBy;

    /** Số link hiện có đã bị chặn theo domain này (chỉ có khi vừa thêm domain, null ở danh sách). */
    private Integer blockedLinks;
}
