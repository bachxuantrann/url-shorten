-- =============================================
--  V10: Báo cáo lạm dụng link
--
--  Người lạ (không cần đăng nhập) báo một link là spam/lừa đảo/mã độc... để admin xem xét.
--  reporter_hash : HMAC(khoá bí mật, IP) cắt 32 ký tự hex, KHÔNG lưu IP thô. Cùng một người báo cùng một
--                  link nhiều lần chỉ được tính một lần (UNIQUE url_id + reporter_hash).
--  status        : OPEN (chờ xử lý) | RESOLVED (admin đã xử lý, ví dụ chặn link) | DISMISSED (không phải lạm dụng)
-- =============================================

CREATE TABLE abuse_reports (
    id            BIGSERIAL    NOT NULL,
    url_id        BIGINT       NOT NULL,
    reason        VARCHAR(20)  NOT NULL,
    details       VARCHAR(500),
    reporter_hash VARCHAR(32)  NOT NULL,
    status        VARCHAR(20)  NOT NULL DEFAULT 'OPEN',
    created_at    TIMESTAMP(6) NOT NULL,
    updated_at    TIMESTAMP(6) NOT NULL,
    created_by    VARCHAR(50)  NOT NULL,
    updated_by    VARCHAR(50)  NOT NULL,

    CONSTRAINT abuse_reports_pkey PRIMARY KEY (id),
    CONSTRAINT fk_abuse_reports_url FOREIGN KEY (url_id) REFERENCES urls (id),
    CONSTRAINT uq_abuse_reports_reporter UNIQUE (url_id, reporter_hash)
);

-- Danh sách của admin: lọc theo status rồi gom theo link
CREATE INDEX idx_abuse_reports_status_url ON abuse_reports (status, url_id);
