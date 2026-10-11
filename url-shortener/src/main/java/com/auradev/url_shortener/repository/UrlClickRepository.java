package com.auradev.url_shortener.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * Ghi hàng loạt vào {@code url_clicks} bằng SQL thuần (JPA không hợp với insert theo lô có
 * {@code ON CONFLICT} trên bảng phân vùng).
 */
@Repository
@RequiredArgsConstructor
public class UrlClickRepository {

    /** Một dòng click đã được làm giàu, sẵn sàng ghi. */
    public record ClickRow(
            UUID eventId,
            long urlId,
            LocalDateTime clickedAt,
            String visitorHash,
            String country,
            String deviceType,
            String browser,
            String os
    ) {
    }

    /**
     * Một câu lệnh duy nhất cho cả lô, bất kể số dòng. Các mảng được {@code unnest} thành bảng rồi:
     * <ol>
     *   <li>chèn vào {@code url_clicks} với {@code ON CONFLICT DO NOTHING} (event giao lại bị bỏ qua);</li>
     *   <li>chèn các (link, ngày, visitor) của khách thật (không phải bot) vào {@code url_daily_visitors};
     *       dòng nào chèn được nghĩa là khách đó mới xuất hiện trong ngày;</li>
     *   <li>upsert {@code url_daily_stats}: cộng số click, click của bot và số khách mới;</li>
     *   <li>cộng {@code urls.click_count}.</li>
     * </ol>
     * Mọi số cộng đều lấy từ các dòng <b>thực sự được chèn</b> (RETURNING), không phải kích thước lô, nên
     * event giao lại không bao giờ bị đếm hai lần; và vì cùng một câu lệnh (một snapshot, một transaction)
     * nên các bộ đếm không thể lệch nhau. Hai instance ghi cùng lúc vẫn đúng: khoá unique của
     * {@code url_daily_visitors} quyết định ai là người "thấy đầu tiên". Các SELECT đều có
     * {@code ORDER BY} để các lô đồng thời khoá dòng theo cùng một thứ tự (tránh deadlock).
     *
     * <p>Không set {@code updated_at}/{@code updated_by} của {@code urls}: một lượt click không phải là
     * một lần sửa link, và {@code updated_at} đang được API hiển thị như "lần sửa cuối".
     */
    private static final String INSERT_SQL = """
            WITH input AS (
                SELECT t.event_id, t.url_id, t.clicked_at, t.visitor_hash, t.country, t.device_type, t.browser, t.os
                FROM unnest(?::text[], ?::bigint[], ?::text[], ?::text[], ?::text[], ?::text[], ?::text[], ?::text[])
                     AS t(event_id, url_id, clicked_at, visitor_hash, country, device_type, browser, os)
            ), ins AS (
                INSERT INTO url_clicks (event_id, url_id, clicked_at, visitor_hash, country, device_type, browser, os)
                SELECT event_id::uuid, url_id, clicked_at::timestamp, visitor_hash, country, device_type, browser, os
                FROM input
                ON CONFLICT DO NOTHING
                RETURNING url_id, clicked_at, visitor_hash, device_type
            ), new_visitors AS (
                INSERT INTO url_daily_visitors (url_id, stat_date, visitor_hash)
                SELECT DISTINCT url_id, clicked_at::date, visitor_hash
                FROM ins
                WHERE visitor_hash IS NOT NULL AND device_type <> 'BOT'
                ORDER BY 1, 2, 3
                ON CONFLICT DO NOTHING
                RETURNING url_id, stat_date
            ), new_visitor_counts AS (
                SELECT url_id, stat_date, count(*) AS visitors FROM new_visitors GROUP BY url_id, stat_date
            ), daily AS (
                SELECT url_id, clicked_at::date AS stat_date, count(*) AS clicks,
                       count(*) FILTER (WHERE device_type = 'BOT') AS bot_clicks
                FROM ins
                GROUP BY url_id, clicked_at::date
            ), daily_upsert AS (
                INSERT INTO url_daily_stats (url_id, stat_date, clicks, bot_clicks, unique_visitors)
                SELECT d.url_id, d.stat_date, d.clicks, d.bot_clicks, COALESCE(v.visitors, 0)
                FROM daily d
                LEFT JOIN new_visitor_counts v ON v.url_id = d.url_id AND v.stat_date = d.stat_date
                ORDER BY d.url_id, d.stat_date
                ON CONFLICT (url_id, stat_date) DO UPDATE SET
                    clicks          = url_daily_stats.clicks + EXCLUDED.clicks,
                    bot_clicks      = url_daily_stats.bot_clicks + EXCLUDED.bot_clicks,
                    unique_visitors = url_daily_stats.unique_visitors + EXCLUDED.unique_visitors
            ), totals AS (
                SELECT url_id, sum(clicks)::bigint AS clicks FROM daily GROUP BY url_id
            )
            UPDATE urls u
            SET click_count = u.click_count + totals.clicks
            FROM totals
            WHERE u.id = totals.url_id
            RETURNING totals.clicks
            """;

    private final JdbcTemplate jdbcTemplate;

    /**
     * Ghi một lô click. Phải gọi trong transaction của caller (JdbcTemplate dùng chung connection).
     *
     * @return số dòng thực sự được chèn (không tính event trùng)
     */
    public int insertBatch(List<ClickRow> rows) {
        if (rows.isEmpty()) {
            return 0;
        }
        return jdbcTemplate.execute((java.sql.Connection connection) -> {
            try (PreparedStatement ps = connection.prepareStatement(INSERT_SQL)) {
                ps.setArray(1, connection.createArrayOf("text", rows.stream().map(r -> r.eventId().toString()).toArray()));
                ps.setArray(2, connection.createArrayOf("bigint", rows.stream().map(ClickRow::urlId).toArray()));
                ps.setArray(3, connection.createArrayOf("text", rows.stream()
                        .map(r -> r.clickedAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)).toArray()));
                ps.setArray(4, connection.createArrayOf("text", rows.stream().map(ClickRow::visitorHash).toArray()));
                ps.setArray(5, connection.createArrayOf("text", rows.stream().map(ClickRow::country).toArray()));
                ps.setArray(6, connection.createArrayOf("text", rows.stream().map(ClickRow::deviceType).toArray()));
                ps.setArray(7, connection.createArrayOf("text", rows.stream().map(ClickRow::browser).toArray()));
                ps.setArray(8, connection.createArrayOf("text", rows.stream().map(ClickRow::os).toArray()));

                int inserted = 0;
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        inserted += (int) rs.getLong(1);
                    }
                }
                return inserted;
            }
        });
    }
}
