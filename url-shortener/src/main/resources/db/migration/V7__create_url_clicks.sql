-- =============================================
--  V7: Bảng url_clicks (dữ liệu click thô), phân vùng theo tháng của clicked_at
--
--  - Khoá chính và unique phải chứa cột phân vùng nên PK là (event_id, clicked_at). Consumer dùng
--    ON CONFLICT DO NOTHING nên một event được giao lại sẽ trúng đúng khoá này, miễn là clicked_at
--    là thời điểm của EVENT (không phải now() của consumer).
--  - Không lưu IP thô: visitor_hash là HMAC(IP + User-Agent, secret, ngày) — xem VisitorHasher.
--  - url_clicks_default hứng dữ liệu nằm ngoài mọi phân vùng đã tạo để không bao giờ mất click;
--    job ClickPartitionJob tạo trước các tháng tới và xoá phân vùng quá hạn lưu trữ.
-- =============================================

CREATE TABLE url_clicks (
    event_id     UUID         NOT NULL,
    url_id       BIGINT       NOT NULL,
    clicked_at   TIMESTAMP(6) NOT NULL,
    visitor_hash VARCHAR(64),
    country      VARCHAR(2),
    device_type  VARCHAR(16)  NOT NULL,
    browser      VARCHAR(32)  NOT NULL,
    os           VARCHAR(32)  NOT NULL,

    CONSTRAINT url_clicks_pkey PRIMARY KEY (event_id, clicked_at),
    CONSTRAINT fk_url_clicks_url FOREIGN KEY (url_id) REFERENCES urls (id)
) PARTITION BY RANGE (clicked_at);

-- Thống kê theo link trong một khoảng thời gian (Phase 7)
CREATE INDEX idx_url_clicks_url_time ON url_clicks (url_id, clicked_at DESC);

CREATE TABLE url_clicks_default PARTITION OF url_clicks DEFAULT;

-- Tháng hiện tại và 3 tháng tới; sau đó job tự tạo tiếp
DO $$
DECLARE
    m DATE;
    i INT;
BEGIN
    FOR i IN 0..3 LOOP
        m := (date_trunc('month', now()) + make_interval(months => i))::date;
        EXECUTE format(
            'CREATE TABLE IF NOT EXISTS %I PARTITION OF url_clicks FOR VALUES FROM (%L) TO (%L)',
            'url_clicks_y' || to_char(m, 'YYYY') || 'm' || to_char(m, 'MM'),
            m,
            (m + interval '1 month')::date);
    END LOOP;
END $$;
