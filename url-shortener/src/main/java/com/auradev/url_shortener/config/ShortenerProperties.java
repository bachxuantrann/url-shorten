package com.auradev.url_shortener.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Cấu hình nghiệp vụ rút gọn link, bind từ {@code application.yaml} (prefix: {@code app.shortener}).
 *
 * <pre>
 * app:
 *   shortener:
 *     base-url: https://sho.rt
 *     code-secret: chuoi-bi-mat-dai
 * </pre>
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.shortener")
public class ShortenerProperties {

    /**
     * Domain công khai của short link, dùng để dựng {@code shortUrl} trong response.
     * Không có dấu {@code /} ở cuối (nếu có sẽ bị cắt).
     */
    private String baseUrl = "http://localhost:8080";

    /**
     * Gốc của giao diện web, dùng khi trình duyệt mở short link mà cần chuyển sang trang của UI (nhập mật khẩu, link
     * không còn dùng được). Để trống = cùng origin (đường dẫn tương đối), đúng khi sau reverse proxy. Dev đặt
     * {@code http://localhost:5173} vì UI chạy ở cổng khác với API.
     */
    private String uiBaseUrl = "";

    /**
     * Khoá bí mật để xáo trộn short code (xem {@code ShortCodeGenerator}).
     * <br>Phải cố định qua các lần deploy: đổi khoá sẽ làm các mã sinh ra sau đó
     * có thể trùng với mã đã cấp trước đó. Luôn đưa vào environment variable ở production.
     */
    private String codeSecret;

    /**
     * Thời gian tối đa một link được cache trong Redis. Cũng là mức "cũ" tối đa nếu một lần
     * xoá cache bị lỡ (Redis lỗi lúc xoá). Mặc định 10 phút.
     */
    private Duration cacheTtl = Duration.ofMinutes(10);

    /**
     * Thời gian cache kết quả "không tồn tại" (và link đã hết hạn) để tránh dò mã làm
     * quá tải DB. Mặc định 1 phút.
     */
    private Duration negativeCacheTtl = Duration.ofMinutes(1);

    /** Số link tối đa được chuyển sang EXPIRED trong một transaction của job hết hạn. */
    private int expiryBatchSize = 500;

    /** Số link (chưa xoá) tối đa mỗi user được sở hữu. */
    private int maxLinksPerUser = 1000;

    /**
     * Chặn URL đích trỏ vào localhost, IP nội bộ/loopback/link-local (kể cả cloud metadata
     * 169.254.169.254), tên miền nội bộ ({@code .local}, {@code .internal}...) và host một nhãn.
     * Tắt ({@code false}) chỉ để dev rút gọn link tới dịch vụ chạy cục bộ.
     */
    private boolean blockPrivateTargets = true;

    /**
     * Các host thuộc về chính dịch vụ này, bị cấm làm URL đích để tránh vòng lặp redirect.
     * Host của {@code base-url} luôn được tính, danh sách này để bổ sung (domain phụ, www...).
     */
    private List<String> ownHosts = new ArrayList<>();
}
