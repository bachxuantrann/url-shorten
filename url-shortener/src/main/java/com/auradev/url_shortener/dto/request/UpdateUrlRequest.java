package com.auradev.url_shortener.dto.request;

import com.auradev.url_shortener.utils.UrlUtils;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Request DTO cập nhật short link (thay thế toàn bộ, kiểu PUT).
 * {@code expiresAt = null} nghĩa là bỏ hạn — link không hết hạn nữa.
 */
@Getter
@Setter
public class UpdateUrlRequest {

    @NotBlank(message = "{validation.url.required}")
    @Size(max = UrlUtils.MAX_URL_LENGTH, message = "{validation.url.size}")
    private String originalUrl;

    @Future(message = "{validation.url.expiresAt.future}")
    private LocalDateTime expiresAt;
}
