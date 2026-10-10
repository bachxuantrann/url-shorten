package com.auradev.url_shortener.controller;

import com.auradev.url_shortener.service.RedirectService;
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

    @GetMapping("/{code}")
    public ResponseEntity<Void> redirect(@PathVariable String code) {
        String target = redirectService.resolveTarget(code);
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(target))
                .cacheControl(CacheControl.noStore())
                .build();
    }
}
