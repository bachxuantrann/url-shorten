package com.auradev.url_shortener.dto.request;

import com.auradev.url_shortener.enums.RoleEnum;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/**
 * Request DTO để admin đổi vai trò của một user: {@code ROLE_ADMIN} (quản trị viên) hoặc {@code ROLE_USER}
 * (người dùng thường). Mọi user đều giữ {@code ROLE_USER}; {@code ROLE_ADMIN} được cấp thêm.
 */
@Getter
@Setter
public class ChangeRoleRequest {

    @NotNull(message = "{validation.role.required}")
    private RoleEnum role;
}
