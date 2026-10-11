package com.auradev.url_shortener.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * Request DTO đặt hoặc đổi mật khẩu bảo vệ link.
 */
@Getter
@Setter
public class SetLinkPasswordRequest {

    @NotBlank(message = "{validation.linkPassword.required}")
    @Size(min = 4, max = 64, message = "{validation.linkPassword.size}")
    private String password;
}
