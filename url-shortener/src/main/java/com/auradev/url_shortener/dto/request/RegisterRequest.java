package com.auradev.url_shortener.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * Request DTO cho API đăng ký tài khoản mới.
 * Validation message sử dụng key từ i18n để hỗ trợ đa ngôn ngữ.
 */
@Getter
@Setter
public class RegisterRequest {

    @NotBlank(message = "{validation.username.required}")
    @Size(min = 4, max = 50, message = "{validation.username.size}")
    @Pattern(
            regexp = "^[a-zA-Z0-9._-]+$",
            message = "{validation.username.pattern}"
    )
    private String username;

    @NotBlank(message = "{validation.email.required}")
    @Email(message = "{validation.email.invalid}")
    private String email;

    @NotBlank(message = "{validation.password.required}")
    @Size(min = 8, max = 100, message = "{validation.password.size}")
    @Pattern(
            regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).+$",
            message = "{validation.password.pattern}"
    )
    private String password;

    @NotBlank(message = "{validation.fullName.required}")
    @Size(max = 100, message = "{validation.fullName.size}")
    private String fullName;
}
