package com.auradev.url_shortener.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response DTO trả về sau khi đăng nhập thành công.
 * Bao gồm access token, refresh token và thông tin người dùng.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoginResponse {

    /** JWT access token — ngắn hạn (mặc định 15 phút) */
    private String accessToken;

    /** JWT refresh token — dài hạn (mặc định 7 ngày) */
    private String refreshToken;

    /** Loại token, luôn là "Bearer" */
    @Builder.Default
    private String tokenType = "Bearer";

    /** Thời gian sống của access token (giây) */
    private long expiresIn;

    /** Thông tin người dùng đã đăng nhập */
    private UserResponse user;
}
