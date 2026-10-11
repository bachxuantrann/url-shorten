package com.auradev.url_shortener.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * Request DTO để admin chặn một link. Lý do bắt buộc để có dấu vết kiểm toán.
 */
@Getter
@Setter
public class BlockUrlRequest {

    @NotBlank(message = "{validation.reason.required}")
    @Size(max = 255, message = "{validation.reason.size}")
    private String reason;
}
