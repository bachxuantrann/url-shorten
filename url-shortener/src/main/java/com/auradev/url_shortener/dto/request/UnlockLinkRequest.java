package com.auradev.url_shortener.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * Request DTO nhập mật khẩu để mở một link được bảo vệ.
 * Giới hạn độ dài để request quá lớn không tốn CPU của BCrypt.
 */
@Getter
@Setter
public class UnlockLinkRequest {

    @NotBlank(message = "{validation.linkPassword.required}")
    @Size(max = 100, message = "{validation.linkPassword.size}")
    private String password;
}
