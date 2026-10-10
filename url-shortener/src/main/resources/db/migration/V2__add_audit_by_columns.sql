-- =============================================
--  V2: Thêm created_by / updated_by (BaseEntity auditing) cho users
--  Dữ liệu cũ được backfill 'system'.
-- =============================================

ALTER TABLE users ADD COLUMN created_by VARCHAR(50);
ALTER TABLE users ADD COLUMN updated_by VARCHAR(50);

UPDATE users SET created_by = 'system', updated_by = 'system';

ALTER TABLE users ALTER COLUMN created_by SET NOT NULL;
ALTER TABLE users ALTER COLUMN updated_by SET NOT NULL;
