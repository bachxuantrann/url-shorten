-- =============================================
--  V8: Thống kê theo ngày của từng link
--
--  url_daily_stats    : một dòng mỗi (link, ngày), được consumer click upsert cộng dồn cùng
--                       câu lệnh ghi url_clicks (xem UrlClickRepository). Giữ vĩnh viễn: url_clicks
--                       thô bị xoá sau thời hạn lưu trữ, bảng này thì không.
--  url_daily_visitors : tập (link, ngày, visitor_hash) đã thấy, dùng để đếm unique visitor CHÍNH XÁC
--                       theo ngày một cách idempotent (ON CONFLICT DO NOTHING cho biết khách có mới
--                       trong ngày hay không). Chỉ cần giữ trong thời hạn lưu trữ dữ liệu thô.
--  stat_date là ngày theo múi giờ của server (clicked_at là giờ địa phương của server).
-- =============================================

CREATE TABLE url_daily_stats (
    url_id          BIGINT NOT NULL,
    stat_date       DATE   NOT NULL,
    clicks          BIGINT NOT NULL DEFAULT 0,
    bot_clicks      BIGINT NOT NULL DEFAULT 0,
    unique_visitors BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT url_daily_stats_pkey PRIMARY KEY (url_id, stat_date),
    CONSTRAINT fk_url_daily_stats_url FOREIGN KEY (url_id) REFERENCES urls (id)
);

CREATE TABLE url_daily_visitors (
    url_id       BIGINT      NOT NULL,
    stat_date    DATE        NOT NULL,
    visitor_hash VARCHAR(64) NOT NULL,

    CONSTRAINT url_daily_visitors_pkey PRIMARY KEY (url_id, stat_date, visitor_hash),
    CONSTRAINT fk_url_daily_visitors_url FOREIGN KEY (url_id) REFERENCES urls (id)
);

-- Xoá dữ liệu quá hạn lưu trữ theo ngày
CREATE INDEX idx_url_daily_visitors_date ON url_daily_visitors (stat_date);
