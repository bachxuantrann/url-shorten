package com.auradev.url_shortener.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

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

    /** Một link trong bảng xếp hạng theo lượt click của cả tài khoản. */
    public record TopLinkRow(String shortCode, String originalUrl, long clicks) {
    }

    /** Số link theo trạng thái (đã tính link ACTIVE quá hạn là EXPIRED). */
    public record StatusCountRow(String status, long count) {
    }

    private final JdbcTemplate jdbcTemplate;

    // ===========================
    //  TOÀN TÀI KHOẢN (mọi link chưa xoá của một người dùng)
    // ===========================

    /**
     * Số link chưa xoá của người dùng theo trạng thái. Link {@code ACTIVE} đã quá hạn nhưng job chưa kịp chuyển
     * được tính là {@code EXPIRED}, khớp với danh sách link và với redirect.
     */
    public List<StatusCountRow> countLinksByStatus(UUID userId, LocalDateTime now) {
        return jdbcTemplate.query("""
                SELECT CASE WHEN status = 'ACTIVE' AND expires_at IS NOT NULL AND expires_at <= ?
                            THEN 'EXPIRED' ELSE status END AS effective_status,
                       count(*)
                FROM urls
                WHERE user_id = ? AND status <> 'DELETED'
                GROUP BY 1
                """,
                (rs, i) -> new StatusCountRow(rs.getString(1), rs.getLong(2)),
                Timestamp.valueOf(now), userId);
    }

    /** Tổng theo ngày của mọi link chưa xoá của người dùng, trong khoảng (cả hai đầu), tăng dần theo ngày. */
    public List<DailyRow> findDailyForUser(UUID userId, LocalDate from, LocalDate to) {
        return jdbcTemplate.query("""
                SELECT d.stat_date, sum(d.clicks), sum(d.bot_clicks), sum(d.unique_visitors)
                FROM url_daily_stats d
                JOIN urls u ON u.id = d.url_id
                WHERE u.user_id = ? AND u.status <> 'DELETED' AND d.stat_date BETWEEN ? AND ?
                GROUP BY d.stat_date
                ORDER BY d.stat_date
                """,
                (rs, i) -> new DailyRow(rs.getDate(1).toLocalDate(), rs.getLong(2), rs.getLong(3), rs.getLong(4)),
                userId, Date.valueOf(from), Date.valueOf(to));
    }

    /** Các link nhiều click nhất của người dùng trong khoảng (bỏ link không có click), tối đa {@code limit}. */
    public List<TopLinkRow> findTopLinksForUser(UUID userId, LocalDate from, LocalDate to, int limit) {
        return jdbcTemplate.query("""
                SELECT u.short_code, u.original_url, sum(d.clicks) AS clicks
                FROM url_daily_stats d
                JOIN urls u ON u.id = d.url_id
                WHERE u.user_id = ? AND u.status <> 'DELETED' AND d.stat_date BETWEEN ? AND ?
                GROUP BY u.id, u.short_code, u.original_url
                HAVING sum(d.clicks) > 0
                ORDER BY clicks DESC, u.short_code
                LIMIT ?
                """,
                (rs, i) -> new TopLinkRow(rs.getString(1), rs.getString(2), rs.getLong(3)),
                userId, Date.valueOf(from), Date.valueOf(to), limit);
    }

    /** Như {@link #findBreakdowns} nhưng gộp mọi link chưa xoá của người dùng, vẫn bằng một lần quét. */
    public List<BreakdownRow> findBreakdownsForUser(UUID userId, LocalDateTime from, LocalDateTime to) {
        return jdbcTemplate.query("""
                SELECT CASE WHEN GROUPING(country) = 0 THEN 'country'
                            WHEN GROUPING(device_type) = 0 THEN 'device'
                            WHEN GROUPING(browser) = 0 THEN 'browser'
                            ELSE 'os' END AS dimension,
                       COALESCE(country, device_type, browser, os) AS name,
                       count(*) AS clicks,
                       count(*) FILTER (WHERE device_type <> 'BOT') AS human_clicks
                FROM url_clicks
                WHERE url_id IN (SELECT id FROM urls WHERE user_id = ? AND status <> 'DELETED')
                  AND clicked_at >= ? AND clicked_at < ?
                GROUP BY GROUPING SETS ((country), (device_type), (browser), (os))
                """,
                (rs, i) -> new BreakdownRow(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getLong(4)),
                userId, Timestamp.valueOf(from), Timestamp.valueOf(to));
    }

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
