-- =============================================
--  V6: Tính năng mở rộng cho link (alias, mật khẩu)
--
--  short_code    : nới từ 16 lên 32 ký tự cho khớp ShortCodes.MAX_LENGTH, nếu không alias dài
--                  từ 17 đến 32 ký tự (đã qua validation) sẽ lỗi khi ghi DB.
--  password_hash : BCrypt hash của mật khẩu bảo vệ link (null = link không có mật khẩu).
-- =============================================

ALTER TABLE urls ALTER COLUMN short_code TYPE VARCHAR(32);

ALTER TABLE urls ADD COLUMN password_hash VARCHAR(100);
