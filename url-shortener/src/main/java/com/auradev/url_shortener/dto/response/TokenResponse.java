package com.auradev.url_shortener.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response DTO dùng để trả về access token mới sau khi refresh.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TokenResponse {

    /** JWT access token mới */
    private String accessToken;

    /** Loại token */
    @Builder.Default
    private String tokenType = "Bearer";

    /** Thời gian sống của access token (giây) */
    private long expiresIn;
}
