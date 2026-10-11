package com.auradev.url_shortener.service;

import java.time.LocalDate;
import java.util.List;

/**
 * Quản lý phân vùng theo tháng của {@code url_clicks}: tạo trước các tháng tới và xoá phân vùng
 * quá hạn lưu trữ.
 */
public interface ClickPartitionService {

    /** Kết quả một lần bảo trì. */
    record Result(boolean ran, List<String> created, List<String> dropped, int deletedVisitorRows) {
    }

    /**
     * Tạo phân vùng cho tháng của {@code today} và {@code monthsAhead - 1} tháng kế tiếp, rồi xoá
     * phân vùng cũ hơn {@code retentionMonths} tháng tính từ tháng của {@code today} ({@code <= 0} = không xoá).
     *
     * <p>An toàn khi nhiều instance cùng chạy: chỉ một instance giữ khoá advisory của Postgres,
     * các instance còn lại bỏ qua lần này ({@code ran = false}).
     */
    Result maintain(LocalDate today, int monthsAhead, int retentionMonths);
}
