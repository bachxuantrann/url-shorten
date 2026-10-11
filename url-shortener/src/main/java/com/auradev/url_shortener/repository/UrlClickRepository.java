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
     * Một câu lệnh duy nhất cho cả lô, bất kể số dòng: các mảng được {@code unnest} thành bảng, chèn
     * với {@code ON CONFLICT DO NOTHING} (event giao lại bị bỏ qua), rồi cộng {@code urls.click_count}
     * <b>chỉ theo các dòng thực sự được chèn</b> (RETURNING) — đếm theo kích thước lô sẽ đếm trùng event
     * giao lại. Chèn và cộng nằm trong cùng một câu lệnh nên không thể lệch nhau.
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
                RETURNING url_id
            ), agg AS (
                SELECT url_id, count(*) AS clicks FROM ins GROUP BY url_id
            )
            UPDATE urls u
            SET click_count = u.click_count + agg.clicks
            FROM agg
            WHERE u.id = agg.url_id
            RETURNING agg.clicks
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
                        inserted += rs.getInt(1);
                    }
                }
                return inserted;
            }
        });
    }
}
