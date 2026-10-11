package com.auradev.url_shortener.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Truy vấn đọc thống kê click. Số câu lệnh cố định (hai) cho mỗi lần xem thống kê một link.
 */
@Repository
@RequiredArgsConstructor
public class UrlStatsRepository {

    public record DailyRow(LocalDate date, long clicks, long botClicks, long uniqueVisitors) {
    }

    /** @param dimension country | device | browser | os */
    public record BreakdownRow(String dimension, String name, long clicks, long humanClicks) {
    }

    private final JdbcTemplate jdbcTemplate;

    /** Các ngày có dữ liệu trong khoảng (cả hai đầu), tăng dần theo ngày. */
    public List<DailyRow> findDaily(long urlId, LocalDate from, LocalDate to) {
        return jdbcTemplate.query("""
                SELECT stat_date, clicks, bot_clicks, unique_visitors
                FROM url_daily_stats
                WHERE url_id = ? AND stat_date BETWEEN ? AND ?
                ORDER BY stat_date
                """,
                (rs, i) -> new DailyRow(rs.getDate(1).toLocalDate(), rs.getLong(2), rs.getLong(3), rs.getLong(4)),
                urlId, Date.valueOf(from), Date.valueOf(to));
    }

    /**
     * Phân rã theo quốc gia / thiết bị / trình duyệt / hệ điều hành bằng <b>một lần quét</b> url_clicks
     * (GROUPING SETS) trong khoảng {@code [from, to)}. Điều kiện theo {@code clicked_at} cho phép
     * Postgres chỉ đọc các phân vùng tháng liên quan. Dữ liệu thô chỉ còn trong thời hạn lưu trữ.
     */
    public List<BreakdownRow> findBreakdowns(long urlId, LocalDateTime from, LocalDateTime to) {
        return jdbcTemplate.query("""
                SELECT CASE WHEN GROUPING(country) = 0 THEN 'country'
                            WHEN GROUPING(device_type) = 0 THEN 'device'
                            WHEN GROUPING(browser) = 0 THEN 'browser'
                            ELSE 'os' END AS dimension,
                       COALESCE(country, device_type, browser, os) AS name,
                       count(*) AS clicks,
                       count(*) FILTER (WHERE device_type <> 'BOT') AS human_clicks
                FROM url_clicks
                WHERE url_id = ? AND clicked_at >= ? AND clicked_at < ?
                GROUP BY GROUPING SETS ((country), (device_type), (browser), (os))
                """,
                (rs, i) -> new BreakdownRow(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getLong(4)),
                urlId, Timestamp.valueOf(from), Timestamp.valueOf(to));
    }
}
