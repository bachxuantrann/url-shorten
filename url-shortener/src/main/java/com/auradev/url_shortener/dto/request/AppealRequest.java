package com.auradev.url_shortener.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * Request DTO để chủ link gửi kháng nghị việc link bị chặn.
 */
@Getter
@Setter
public class AppealRequest {

    @NotBlank(message = "{validation.appeal.message.required}")
    @Size(max = 500, message = "{validation.appeal.message.size}")
    private String message;
}
