package com.auradev.url_shortener.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * Request DTO cho API đổi mật khẩu.
 * Logic kiểm tra newPassword == confirmPassword thực hiện ở tầng Service.
 */
@Getter
@Setter
public class ChangePasswordRequest {

    @NotBlank(message = "{validation.oldPassword.required}")
    private String oldPassword;

    @NotBlank(message = "{validation.newPassword.required}")
    @Size(min = 8, max = 100, message = "{validation.newPassword.size}")
    private String newPassword;

    @NotBlank(message = "{validation.confirmPassword.required}")
    private String confirmPassword;
}
