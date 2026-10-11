package com.auradev.url_shortener.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Cấu hình theo dõi click, bind từ {@code application-*.yaml} (prefix: {@code app.click}).
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.click")
public class ClickProperties {

    /** Topic Kafka chứa các {@code ClickEvent}, key = short code. */
    private String topic = "url-clicks";

    /** Số partition khi tạo topic (khai báo tường minh vì broker tự tạo topic chỉ có 1 partition). */
    private int partitions = 3;

    /** Số bản sao khi tạo topic (broker dev/prod hiện chỉ có 1). */
    private short replicas = 1;

    /** Số thread consumer chạy song song (không nên lớn hơn số partition). */
    private int consumerConcurrency = 1;

    /** Số thread gửi event lên Kafka (tách khỏi thread xử lý request). */
    private int publisherThreads = 2;

    /**
     * Sức chứa hàng đợi gửi event. Đầy thì event bị bỏ (đếm lại) thay vì làm chậm redirect.
     */
    private int publisherQueueSize = 10_000;

    /**
     * Khoá HMAC để băm danh tính khách truy cập (IP + User-Agent). Đổi khoá chỉ làm đếm unique
     * visitor bị đứt quãng tại thời điểm đổi, không ảnh hưởng gì khác (khác với {@code code-secret}).
     */
    private String visitorSecret;

    /**
     * Đường dẫn tới file MaxMind GeoLite2-Country ({@code .mmdb}) để tra quốc gia từ IP. Để trống =
     * không ghi quốc gia. Đã đặt mà file không tồn tại thì ứng dụng không khởi động. File chỉ được đọc
     * lúc khởi động: cập nhật file (geoipupdate) xong phải khởi động lại.
     */
    private String geoipDatabase;

    /** Job đối soát bộ đếm (chạy mỗi {@code app.click.reconcile-job-interval}, mặc định một ngày). */
    private boolean reconcileJobEnabled = true;

    /**
     * Tự sửa {@code urls.click_count} lệch so với tổng {@code url_daily_stats}. Mặc định tắt: job chỉ
     * báo lỗi (log). Bộ đếm chỉ lệch khi có sự cố hoặc ai đó sửa tay dữ liệu, nên nên xem nguyên nhân
     * trước khi bật.
     */
    private boolean reconcileRepair = false;

    /** Số link lệch tối đa xử lý/báo cáo mỗi lần chạy. */
    private int reconcileBatchSize = 1000;

    /** Số ngày gần nhất mà thống kê theo ngày được đối chiếu lại với {@code url_clicks} thô. */
    private int reconcileWindowDays = 2;

    /** Job tạo trước phân vùng tháng tới và xoá phân vùng quá hạn. */
    private boolean partitionJobEnabled = true;

    /** Số tháng tới được tạo sẵn phân vùng (tính cả tháng hiện tại). */
    private int partitionMonthsAhead = 3;

    /** Giữ dữ liệu click thô bao nhiêu tháng (không tính tháng hiện tại); {@code <= 0} = không xoá. */
    private int retentionMonths = 13;

    public String getDeadLetterTopic() {
        return topic + ".DLT";
    }
}
