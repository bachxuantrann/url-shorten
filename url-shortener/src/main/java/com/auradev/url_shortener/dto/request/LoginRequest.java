package com.auradev.url_shortener.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/**
 * Request DTO cho API đăng nhập.
 */
@Getter
@Setter
public class LoginRequest {

    @NotBlank(message = "{validation.credential.username.required}")
    private String username;

    @NotBlank(message = "{validation.credential.password.required}")
    private String password;
}
