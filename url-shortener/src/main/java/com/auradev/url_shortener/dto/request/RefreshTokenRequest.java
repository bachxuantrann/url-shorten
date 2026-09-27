package com.auradev.url_shortener.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/**
 * Request DTO cho API làm mới access token.
 */
@Getter
@Setter
public class RefreshTokenRequest {

    @NotBlank(message = "{validation.refreshToken.required}")
    private String refreshToken;
}
