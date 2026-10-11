package com.auradev.url_shortener.service;

import java.time.LocalDate;
import java.util.List;

/**
 * Đối soát các bộ đếm click, vốn được cập nhật cùng nhau trong một câu lệnh nên bình thường luôn khớp.
 * Chỉ lệch khi có sự cố hoặc dữ liệu bị sửa tay; đối soát để phát hiện (và tuỳ chọn sửa) sớm.
 *
 * <ol>
 *   <li><b>Bộ đếm link:</b> {@code urls.click_count} phải bằng tổng {@code url_daily_stats.clicks}. So
 *       với bảng thống kê (giữ vĩnh viễn) chứ không so với {@code url_clicks}, vì dữ liệu thô bị xoá
 *       sau thời hạn lưu trữ.</li>
 *   <li><b>Thống kê ngày:</b> trong {@code windowDays} ngày gần nhất, {@code url_daily_stats.clicks} phải
 *       bằng số dòng thô của {@code url_clicks}. Chỉ báo cáo, không tự sửa.</li>
 * </ol>
 */
public interface ClickReconciliationService {

    /** Một link có bộ đếm lệch so với tổng thống kê ngày. */
    record CounterMismatch(long urlId, String shortCode, long counter, long statsTotal) {
    }

    /**
     * @param counterMismatches link lệch bộ đếm (tối đa {@code limit}); khi đã sửa thì là trạng thái trước khi sửa
     * @param truncated         còn nhiều link lệch hơn số đã liệt kê
     * @param repaired          số link đã được sửa bộ đếm
     * @param dayMismatches     số cặp (link, ngày) có thống kê lệch so với dữ liệu thô trong cửa sổ
     */
    record Result(List<CounterMismatch> counterMismatches, boolean truncated, int repaired, int dayMismatches) {
    }

    Result reconcile(LocalDate today, int windowDays, int limit, boolean repair);
}
