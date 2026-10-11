package com.auradev.url_shortener.dto.request;

import com.auradev.url_shortener.utils.ShortCodes;
import com.auradev.url_shortener.utils.UrlUtils;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Request DTO tạo short link mới (người dùng đã đăng nhập).
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

    /**
     * Alias tuỳ chọn thay cho mã sinh tự động (ví dụ {@code my-github}). Phân biệt hoa thường,
     * không đổi được sau khi tạo. Trùng hoặc thuộc danh sách dành riêng trả 409.
     */
    @Size(min = ShortCodes.MIN_LENGTH, max = ShortCodes.MAX_LENGTH, message = "{validation.alias.size}")
    @Pattern(regexp = "^[0-9a-zA-Z_-]+$", message = "{validation.alias.pattern}")
    private String alias;

    /** Mật khẩu bảo vệ link (tuỳ chọn). Người truy cập phải nhập đúng mới nhận được URL đích. */
    @Size(min = 4, max = 64, message = "{validation.linkPassword.size}")
    private String password;
}
