package com.auradev.url_shortener.dto.request;

import com.auradev.url_shortener.utils.UrlUtils;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Request DTO tạo link <b>ẩn danh</b> ({@code POST /api/v1/public/urls}).
 *
 * <p>Cố ý không có {@code alias} và {@code password}: alias cho người không đăng nhập sẽ bị chiếm
 * dụng hàng loạt, và link ẩn danh không có chủ để quản lý mật khẩu. Các field đó nếu client gửi
 * kèm sẽ bị bỏ qua.
 */
@Getter
@Setter
public class CreatePublicUrlRequest {

    @NotBlank(message = "{validation.url.required}")
    @Size(max = UrlUtils.MAX_URL_LENGTH, message = "{validation.url.size}")
    private String originalUrl;

    @Future(message = "{validation.url.expiresAt.future}")
    private LocalDateTime expiresAt;
}
