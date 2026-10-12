package com.auradev.url_shortener.dto.request;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * Request DTO để admin từ chối một kháng nghị; lời phản hồi cho chủ link là tuỳ chọn.
 */
@Getter
@Setter
public class ResolveAppealRequest {

    @Size(max = 255, message = "{validation.appeal.response.size}")
    private String response;
}
