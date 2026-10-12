package com.auradev.url_shortener.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Cấu hình để backend ĐỌC log từ OpenObserve cho trang "Giám sát" của admin (prefix {@code app.openobserve}). Khác với phần
 * xuất OTLP (ghi dữ liệu vào OpenObserve, cấu hình ở {@code management.*}).
 *
 * <pre>
 * app:
 *   openobserve:
 *     url: http://localhost:5080       # gốc API, backend gọi (trong Docker: http://openobserve:5080)
 *     ui-url: http://localhost:5080    # địa chỉ trình duyệt của admin mở được, cho nút "Mở OpenObserve"
 *     email / password                 # tài khoản đọc (cùng giá trị OPENOBSERVE_EMAIL / OPENOBSERVE_PASSWORD)
 * </pre>
 *
 * Thiếu {@code url}, {@code email} hoặc {@code password} thì tính năng coi như chưa cấu hình (trang báo rõ, không lỗi).
 * Thông tin đăng nhập chỉ nằm ở backend, không bao giờ gửi xuống trình duyệt.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.openobserve")
public class OpenObserveProperties {

    private String url = "";

    private String uiUrl = "";

    private String email = "";

    private String password = "";

    /** Tổ chức (organization) trong OpenObserve; mặc định là {@code default}. */
    private String organization = "default";

    /** Tên stream log mà app xuất vào (OTLP logs vào stream {@code default}). */
    private String logStream = "default";

    /** Thời gian chờ tối đa mỗi lần gọi OpenObserve. */
    private Duration timeout = Duration.ofSeconds(5);

    public boolean isConfigured() {
        return notBlank(url) && notBlank(email) && notBlank(password);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
