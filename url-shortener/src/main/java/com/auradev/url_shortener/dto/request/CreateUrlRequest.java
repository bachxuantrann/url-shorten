package com.auradev.url_shortener.dto.request;

import com.auradev.url_shortener.utils.UrlUtils;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Request DTO tạo short link mới.
 * Định dạng URL (scheme, host...) được kiểm tra ở tầng Service qua {@link UrlUtils}.
 */
@Getter
@Setter
public class CreateUrlRequest {

    @NotBlank(message = "{validation.url.required}")
    @Size(max = UrlUtils.MAX_URL_LENGTH, message = "{validation.url.size}")
    private String originalUrl;

    /** Thời điểm hết hạn (tuỳ chọn, phải ở tương lai). Bỏ trống = không hết hạn. */
    @Future(message = "{validation.url.expiresAt.future}")
    private LocalDateTime expiresAt;
}
