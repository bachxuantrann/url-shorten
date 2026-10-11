-- =============================================
--  V5: Chống lạm dụng
--
--  urls.blocked_reason : lý do admin/hệ thống chặn link (chỉ có giá trị khi status = BLOCKED)
--  blocked_domains     : danh sách domain bị cấm làm URL đích (khớp cả subdomain)
-- =============================================

ALTER TABLE urls ADD COLUMN blocked_reason VARCHAR(255);

CREATE TABLE blocked_domains (
    id         BIGSERIAL    NOT NULL,
    domain     VARCHAR(253) NOT NULL,
    reason     VARCHAR(255),
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    created_by VARCHAR(50)  NOT NULL,
    updated_by VARCHAR(50)  NOT NULL,

    CONSTRAINT blocked_domains_pkey      PRIMARY KEY (id),
    CONSTRAINT uq_blocked_domains_domain UNIQUE (domain)
);
