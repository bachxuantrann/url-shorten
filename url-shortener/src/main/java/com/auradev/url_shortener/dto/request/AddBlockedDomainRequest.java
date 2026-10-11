package com.auradev.url_shortener.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * Request DTO để admin thêm một domain vào blocklist.
 */
@Getter
@Setter
public class AddBlockedDomainRequest {

    /** Domain bị cấm (cả subdomain), ví dụ {@code evil.example.com}. Cú pháp kiểm tra ở service. */
    @NotBlank(message = "{validation.domain.required}")
    @Size(max = 253, message = "{validation.domain.size}")
    private String domain;

    @Size(max = 255, message = "{validation.reason.size}")
    private String reason;

    /** Chặn luôn các link hiện có trỏ tới domain này (mặc định: có). */
    private boolean blockExistingLinks = true;
}
