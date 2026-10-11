package com.auradev.url_shortener.dto.response;

import com.auradev.url_shortener.dto.response.UrlStatsResponse.BreakdownItem;
import com.auradev.url_shortener.dto.response.UrlStatsResponse.DailyStat;
import com.auradev.url_shortener.dto.response.UrlStatsResponse.Totals;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

/**
 * Tổng quan thống kê của <b>mọi link chưa xoá</b> của một người dùng trong một khoảng ngày (cả hai đầu đều tính,
 * theo múi giờ của server). Cùng hình dạng với {@link UrlStatsResponse} ở các phần {@code totals}, {@code daily}
 * và phân rã, kèm số link theo trạng thái và các link nhiều click nhất.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StatsOverviewResponse {

    private LocalDate from;
    private LocalDate to;

    /** Số link hiện có, không phụ thuộc khoảng ngày. */
    private LinkCounts links;

    private Totals totals;

    /** Mỗi ngày trong khoảng một phần tử, ngày không có click được điền 0. */
    private List<DailyStat> daily;

    /** Các link nhiều click nhất trong khoảng (bỏ link không có click). */
    private List<TopLink> topLinks;

    private List<BreakdownItem> countries;
    private List<BreakdownItem> devices;
    private List<BreakdownItem> browsers;
    private List<BreakdownItem> operatingSystems;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LinkCounts {
        private long total;
        private long active;
        private long disabled;
        /** Gồm cả link ACTIVE đã quá hạn mà job chưa kịp chuyển trạng thái. */
        private long expired;
        private long blocked;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TopLink {
        private String shortCode;
        private String shortUrl;
        private String originalUrl;
        private long clicks;
    }
}
