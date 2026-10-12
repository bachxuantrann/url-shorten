-- =============================================
--  V11: Kháng nghị link bị chặn
--
--  Chủ link bị admin chặn gửi một lời kháng nghị (message) để admin xem xét lại.
--  status   : OPEN (chờ xem xét) | ACCEPTED (admin đã gỡ chặn) | REJECTED (admin từ chối, kèm response)
--  user_id  : chủ link lúc kháng nghị (UUID, cùng kiểu với users.id)
--  Mỗi link chỉ có tối đa MỘT kháng nghị OPEN (chỉ mục UNIQUE một phần); xử lý xong thì được gửi kháng nghị mới.
-- =============================================

CREATE TABLE link_appeals (
    id         BIGSERIAL    NOT NULL,
    url_id     BIGINT       NOT NULL,
    user_id    UUID         NOT NULL,
    message    VARCHAR(500) NOT NULL,
    status     VARCHAR(20)  NOT NULL DEFAULT 'OPEN',
    response   VARCHAR(255),
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    created_by VARCHAR(50)  NOT NULL,
    updated_by VARCHAR(50)  NOT NULL,

    CONSTRAINT link_appeals_pkey PRIMARY KEY (id),
    CONSTRAINT fk_link_appeals_url FOREIGN KEY (url_id) REFERENCES urls (id),
    CONSTRAINT fk_link_appeals_user FOREIGN KEY (user_id) REFERENCES users (id)
);

-- Một kháng nghị đang mở cho mỗi link
CREATE UNIQUE INDEX uq_link_appeals_open ON link_appeals (url_id) WHERE status = 'OPEN';

-- Danh sách của admin (lọc theo status, sắp theo thời gian) và tìm kháng nghị gần nhất của một lô link
CREATE INDEX idx_link_appeals_status_created ON link_appeals (status, created_at);
CREATE INDEX idx_link_appeals_url ON link_appeals (url_id, id DESC);
