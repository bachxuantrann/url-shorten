package com.auradev.url_shortener.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

/**
 * Thống kê click của một link trong một khoảng ngày (cả hai đầu đều tính, theo múi giờ của server).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UrlStatsResponse {

    private String shortCode;
    private LocalDate from;
    private LocalDate to;

    private Totals totals;

    /** Mỗi ngày trong khoảng một phần tử, ngày không có click được điền 0. */
    private List<DailyStat> daily;

    /** Top quốc gia (mã ISO, {@code Unknown} nếu không rõ); chỉ tính khách thật, không tính bot. */
    private List<BreakdownItem> countries;

    /** Loại thiết bị: DESKTOP / MOBILE / TABLET / BOT / UNKNOWN (có cả bot). */
    private List<BreakdownItem> devices;

    /** Top trình duyệt, chỉ khách thật. */
    private List<BreakdownItem> browsers;

    /** Top hệ điều hành, chỉ khách thật. */
    private List<BreakdownItem> operatingSystems;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Totals {
        /** Tổng click, gồm cả bot. */
        private long clicks;
        /** Trong đó click của bot/crawler/trình xem trước. */
        private long botClicks;
        /**
         * Tổng số khách duy nhất <b>theo từng ngày</b> cộng lại: một người quay lại ở hai ngày khác
         * nhau được tính hai lần, vì danh tính chỉ băm trong phạm vi một ngày (quyền riêng tư).
         */
        private long uniqueVisitors;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DailyStat {
        private LocalDate date;
        private long clicks;
        private long botClicks;
        private long uniqueVisitors;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BreakdownItem {
        private String name;
        private long clicks;
    }
}
