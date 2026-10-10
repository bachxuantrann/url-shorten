-- =============================================
--  V3: Bảng urls + sequence sinh short code
--
--  short_code_seq: nguồn số duy nhất để sinh short code (xem ShortCodeGenerator).
--  Tách khỏi urls.id để mã công khai không phụ thuộc khoá chính nội bộ.
-- =============================================

CREATE SEQUENCE short_code_seq START WITH 1 INCREMENT BY 1;

CREATE TABLE urls (
    id           BIGSERIAL    NOT NULL,
    user_id      UUID,
    short_code   VARCHAR(16)  NOT NULL,
    original_url TEXT         NOT NULL,
    status       VARCHAR(20)  NOT NULL,
    expires_at   TIMESTAMP(6),
    click_count  BIGINT       NOT NULL DEFAULT 0,
    deleted_at   TIMESTAMP(6),
    created_at   TIMESTAMP(6) NOT NULL,
    updated_at   TIMESTAMP(6) NOT NULL,
    created_by   VARCHAR(50)  NOT NULL,
    updated_by   VARCHAR(50)  NOT NULL,

    CONSTRAINT urls_pkey          PRIMARY KEY (id),
    CONSTRAINT uq_urls_short_code UNIQUE (short_code),
    CONSTRAINT fk_urls_user       FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT urls_status_check  CHECK (status IN ('ACTIVE', 'DISABLED', 'EXPIRED', 'BLOCKED', 'DELETED'))
);

-- Danh sách link của một user, mới nhất trước
CREATE INDEX idx_urls_user_created ON urls (user_id, created_at DESC);
