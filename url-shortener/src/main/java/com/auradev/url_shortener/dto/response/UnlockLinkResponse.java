package com.auradev.url_shortener.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Kết quả mở khoá link có mật khẩu: URL đích để client (trang nhập mật khẩu) tự chuyển hướng tới.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UnlockLinkResponse {

    private String originalUrl;
}
