package com.auradev.url_shortener.service.impl;

import com.auradev.url_shortener.service.ClickReconciliationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;

/**
 * Triển khai {@link ClickReconciliationService}. Mỗi lần chạy là số câu lệnh cố định (hai truy vấn phát
 * hiện, và khi sửa thì thêm một khoá + một cập nhật cho cả lô), không phụ thuộc số link lệch.
 *
 * <p><b>Sửa an toàn với việc ghi click đang diễn ra:</b> trong một transaction, trước hết khoá các dòng
 * {@code urls} cần sửa ({@code FOR UPDATE}), rồi mới tính lại tổng từ {@code url_daily_stats} bằng một câu
 * lệnh mới. Mọi lô ghi click đều cập nhật {@code urls.click_count} trong cùng transaction với thống kê nên
 * sau khi giữ khoá, mọi lô đã commit đều đã nhìn thấy được và lô đang chờ sẽ cộng tiếp lên giá trị đã sửa.
 * Không có cách đọc rồi ghi cách quãng làm mất lượt cộng.
 */
@Slf4j
@Service
public class ClickReconciliationServiceImpl implements ClickReconciliationService {

    /** Số dòng log chi tiết tối đa mỗi lần chạy, tránh ngập log khi lệch hàng loạt. */
    private static final int MAX_LOGGED = 20;

    private static final String SUM_CLICKS = "COALESCE((SELECT sum(s.clicks) FROM url_daily_stats s WHERE s.url_id = u.id), 0)";

    private final JdbcTemplate        jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public ClickReconciliationServiceImpl(JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager) {
        this.jdbcTemplate        = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public Result reconcile(LocalDate today, int windowDays, int limit, boolean repair) {
        int fetch = Math.max(1, limit);
        List<CounterMismatch> found = jdbcTemplate.query("""
                SELECT u.id, u.short_code, u.click_count, COALESCE(s.total, 0)
                FROM urls u
                LEFT JOIN (SELECT url_id, sum(clicks) AS total FROM url_daily_stats GROUP BY url_id) s ON s.url_id = u.id
                WHERE u.click_count <> COALESCE(s.total, 0)
                ORDER BY u.id
                LIMIT ?
                """,
                (rs, i) -> new CounterMismatch(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getLong(4)),
                fetch + 1);
        boolean truncated = found.size() > fetch;
        List<CounterMismatch> mismatches = truncated ? found.subList(0, fetch) : found;

        for (CounterMismatch m : mismatches.stream().limit(MAX_LOGGED).toList()) {
            log.warn("Click counter mismatch: shortCode={}, urls.click_count={}, sum(url_daily_stats)={}",
                    m.shortCode(), m.counter(), m.statsTotal());
        }

        int repaired = repair && !mismatches.isEmpty() ? repairCounters(mismatches) : 0;
        int dayMismatches = countDayMismatches(today, windowDays);
        if (dayMismatches > 0) {
            log.warn("{} (link, day) pair(s) in the last {} day(s) have url_daily_stats.clicks different from url_clicks",
                    dayMismatches, windowDays);
        }
        return new Result(List.copyOf(mismatches), truncated, repaired, dayMismatches);
    }

    private int repairCounters(List<CounterMismatch> mismatches) {
        Long[] ids = mismatches.stream().map(CounterMismatch::urlId).sorted().toArray(Long[]::new);
        Integer repaired = transactionTemplate.execute(status -> {
            // 1) Khoá trước (theo thứ tự id) để các lô ghi click đang chạy phải chờ
            jdbcTemplate.query("SELECT id FROM urls WHERE id = ANY(?) ORDER BY id FOR UPDATE",
                    ps -> ps.setArray(1, ps.getConnection().createArrayOf("bigint", ids)),
                    (rs, i) -> rs.getLong(1));
            // 2) Tính lại bằng câu lệnh mới (thấy mọi thứ đã commit) và chỉ sửa dòng còn lệch
            List<String> fixed = jdbcTemplate.query(
                    "UPDATE urls u SET click_count = " + SUM_CLICKS + "::bigint"
                            + " WHERE u.id = ANY(?) AND u.click_count <> " + SUM_CLICKS
                            + " RETURNING u.short_code",
                    ps -> ps.setArray(1, ps.getConnection().createArrayOf("bigint", ids)),
                    (rs, i) -> rs.getString(1));
            return fixed.size();
        });
        int count = repaired == null ? 0 : repaired;
        log.warn("Click counter reconciliation repaired {} link(s)", count);
        return count;
    }

    private int countDayMismatches(LocalDate today, int windowDays) {
        LocalDate since = today.minusDays(Math.max(1, windowDays) - 1L);
        Integer count = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM (
                    SELECT 1
                    FROM (SELECT url_id, stat_date, clicks FROM url_daily_stats WHERE stat_date BETWEEN ? AND ?) d
                    FULL JOIN (
                        SELECT url_id, clicked_at::date AS day, count(*) AS n
                        FROM url_clicks
                        WHERE clicked_at >= ? AND clicked_at < ?
                        GROUP BY url_id, clicked_at::date
                    ) c ON c.url_id = d.url_id AND c.day = d.stat_date
                    WHERE COALESCE(d.clicks, 0) <> COALESCE(c.n, 0)
                ) mismatched
                """, Integer.class,
                Date.valueOf(since), Date.valueOf(today),
                Timestamp.valueOf(since.atStartOfDay()), Timestamp.valueOf(today.plusDays(1).atStartOfDay()));
        return count == null ? 0 : count;
    }
}
