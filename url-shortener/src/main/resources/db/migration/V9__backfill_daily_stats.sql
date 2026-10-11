-- =============================================
--  V9: Dựng thống kê theo ngày từ các click đã có trong url_clicks (dữ liệu của Phase 6)
--
--  Chỉ chạy đúng một lần khi hai bảng của V8 còn rỗng. Bot không được tính vào unique visitor
--  (giống câu lệnh ghi hằng ngày). Dữ liệu thô đã bị xoá trước đó thì không khôi phục được.
-- =============================================

INSERT INTO url_daily_visitors (url_id, stat_date, visitor_hash)
SELECT DISTINCT url_id, clicked_at::date, visitor_hash
FROM url_clicks
WHERE visitor_hash IS NOT NULL AND device_type <> 'BOT'
ON CONFLICT DO NOTHING;

INSERT INTO url_daily_stats (url_id, stat_date, clicks, bot_clicks, unique_visitors)
SELECT c.url_id, c.day, c.clicks, c.bot_clicks, COALESCE(v.visitors, 0)
FROM (
    SELECT url_id, clicked_at::date AS day, count(*) AS clicks,
           count(*) FILTER (WHERE device_type = 'BOT') AS bot_clicks
    FROM url_clicks
    GROUP BY url_id, clicked_at::date
) c
LEFT JOIN (
    SELECT url_id, stat_date, count(*) AS visitors
    FROM url_daily_visitors
    GROUP BY url_id, stat_date
) v ON v.url_id = c.url_id AND v.stat_date = c.day
ON CONFLICT DO NOTHING;
