package com.auradev.url_shortener.controller;

import com.auradev.url_shortener.constant.AppConstant;
import com.auradev.url_shortener.dto.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;

/**
 * Cấu hình công khai mà giao diện cần biết trước khi đăng nhập.
 *
 * <p>Hiện chỉ có múi giờ của máy chủ: mọi thời điểm trong API (hạn dùng, ngày tạo...) là {@code LocalDateTime}, tức giờ
 * "treo tường" theo múi giờ của máy chủ và không kèm múi giờ. Giao diện cần múi giờ này để quy đổi sang giờ của người
 * dùng (và ngược lại khi gửi hạn dùng), nếu không cùng một hạn dùng lệch đi nhiều giờ giữa máy dev (giờ địa phương) và
 * container prod (thường là UTC).
 */
@RestController
@RequestMapping(AppConstant.API_PUBLIC + "/config")
@RequiredArgsConstructor
public class PublicConfigController {

    private final Clock clock;

    /** {@code GET /api/v1/public/config} → {@code {"timezone": "Asia/Ho_Chi_Minh"}} (mã vùng IANA). */
    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, String>>> config() {
        return ResponseEntity.ok()
                // Múi giờ gần như không đổi: cho trình duyệt cache ngắn để không gọi lại ở mỗi trang
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(10)).cachePublic())
                .body(ApiResponse.success(null, Map.of("timezone", clock.getZone().getId())));
    }
}
