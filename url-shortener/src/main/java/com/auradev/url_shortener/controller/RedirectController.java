package com.auradev.url_shortener.controller;

import com.auradev.url_shortener.exception.AppException;
import com.auradev.url_shortener.service.ClickPublisher;
import com.auradev.url_shortener.service.RedirectService;
import com.auradev.url_shortener.utils.AppMetrics;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * Endpoint công khai của short link: {@code GET /{code}} → {@code 302 Found} tới URL đích.
 *
 * <p>Dùng 302 (không phải 301) và {@code Cache-Control: no-store} để trình duyệt/proxy không
 * lưu redirect: link có thể bị tắt, đổi đích hoặc hết hạn bất cứ lúc nào, và về sau mọi lượt
 * click phải đi qua server để được đếm.
 *
 * <p>Route được mở công khai trong {@code SecurityConfig} cho mọi đường dẫn một đoạn không
 * thuộc danh sách mã dành riêng (xem {@link com.auradev.url_shortener.utils.ShortCodes}).
 */
@RestController
@RequiredArgsConstructor
public class RedirectController {

    private final RedirectService redirectService;
    private final ClickPublisher  clickPublisher;
    private final AppMetrics      metrics;

    @GetMapping("/{code}")
    public ResponseEntity<Void> redirect(@PathVariable String code, HttpServletRequest request) {
        Timer.Sample sample = metrics.startTimer();
        String outcome = "redirect";
        try {
            String target = redirectService.resolveTarget(code);
            // Chỉ tính click khi redirect thành công (không phải 403/404/410); không bao giờ ném lỗi
            clickPublisher.publish(code, request);
            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(URI.create(target))
                    .cacheControl(CacheControl.noStore())
                    .build();
        } catch (AppException e) {
            outcome = e.getErrorCode().name().toLowerCase();
            throw e;
        } finally {
            // Tag outcome có số giá trị hữu hạn (ErrorCode); lỗi bất ngờ vẫn được đo là "redirect" chứ không
            // tạo tag mới, vì đã có trace/log cho trường hợp đó
            metrics.recordRedirect(sample, outcome);
        }
    }
}
