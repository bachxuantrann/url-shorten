package com.auradev.url_shortener.controller;

import com.auradev.url_shortener.config.ShortenerProperties;
import com.auradev.url_shortener.exception.AppException;
import com.auradev.url_shortener.exception.ErrorCode;
import com.auradev.url_shortener.service.ClickPublisher;
import com.auradev.url_shortener.service.RedirectService;
import com.auradev.url_shortener.utils.AppMetrics;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Locale;

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

    /** Route của UI; hai đoạn nên không đụng chạm với {@code /{code}}. */
    private static final String UNLOCK_PAGE_PREFIX      = "/unlock/";
    private static final String UNAVAILABLE_PAGE_PREFIX = "/unavailable/";

    private final RedirectService   redirectService;
    private final ClickPublisher    clickPublisher;
    private final AppMetrics        metrics;
    private final ShortenerProperties properties;

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
            // Trình duyệt (chấp nhận HTML) được đưa sang trang của UI thay vì thấy JSON: link có mật khẩu sang trang
            // nhập mật khẩu, link chết (không có / đã tắt / hết hạn / đã xoá / bị chặn) sang trang giải thích. Client API
            // (không nhận HTML) vẫn nhận đúng mã trạng thái và JSON. Không tính click ở các nhánh này.
            if (acceptsHtml(request)) {
                String page = uiPageFor(e.getErrorCode(), code);
                if (page != null) {
                    return ResponseEntity.status(HttpStatus.FOUND)
                            .location(URI.create(uiBase() + page))
                            .cacheControl(CacheControl.noStore())
                            .build();
                }
            }
            throw e;
        } finally {
            // Tag outcome có số giá trị hữu hạn (ErrorCode); lỗi bất ngờ vẫn được đo là "redirect" chứ không
            // tạo tag mới, vì đã có trace/log cho trường hợp đó
            metrics.recordRedirect(sample, outcome);
        }
    }

    /**
     * Trang UI tương ứng với lỗi, hoặc null nếu lỗi này không có trang riêng. Mã chỉ được ghép vào đường dẫn khi đã
     * chắc chắn nó hợp lệ (link tồn tại và có mật khẩu); các trang lỗi không chứa mã nên không phản chiếu đầu vào.
     */
    private static String uiPageFor(ErrorCode error, String code) {
        return switch (error) {
            case URL_PASSWORD_REQUIRED -> UNLOCK_PAGE_PREFIX + code;
            case URL_NOT_FOUND         -> UNAVAILABLE_PAGE_PREFIX + "not-found";
            case URL_GONE              -> UNAVAILABLE_PAGE_PREFIX + "gone";
            case URL_ACCESS_BLOCKED    -> UNAVAILABLE_PAGE_PREFIX + "blocked";
            default                    -> null;
        };
    }

    /** Gốc của UI: rỗng (đường dẫn tương đối, cùng origin sau reverse proxy) hoặc địa chỉ riêng khi dev (Vite). */
    private String uiBase() {
        String base = properties.getUiBaseUrl();
        return base == null ? "" : base.replaceAll("/+$", "");
    }

    /** Client chấp nhận HTML (trình duyệt gõ/nhấp link); curl, fetch mặc định gửi {@code *}/{@code *} thì không tính. */
    private static boolean acceptsHtml(HttpServletRequest request) {
        String accept = request.getHeader(HttpHeaders.ACCEPT);
        return accept != null && accept.toLowerCase(Locale.ROOT).contains("text/html");
    }
}
