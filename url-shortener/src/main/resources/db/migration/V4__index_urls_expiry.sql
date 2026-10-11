-- =============================================
--  V4: Index cho job chuyển link hết hạn sang EXPIRED
--
--  Partial index chỉ chứa link ACTIVE có hạn, nên rất nhỏ và job quét hết hạn
--  (ORDER BY expires_at LIMIT n) không phải duyệt cả bảng urls.
-- =============================================

CREATE INDEX idx_urls_active_expiry
    ON urls (expires_at)
    WHERE status = 'ACTIVE' AND expires_at IS NOT NULL;
