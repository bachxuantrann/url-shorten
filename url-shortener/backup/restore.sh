#!/bin/sh
# =============================================
#  Khôi phục một bản sao lưu vào Postgres.
#
#  Dùng (chạy trong thư mục url-shortener/ trên máy chủ):
#    docker compose -f docker-compose.prod.yml --env-file .env.prod run --rm --entrypoint sh backup \
#      /scripts/restore.sh /backups/url_shortener_prod_20261011T020000Z.dump
#
#  Mặc định khôi phục vào một DB TẠM <db>_restore_check để KIỂM TRA bản backup dùng được (rồi xoá DB tạm).
#  Khôi phục thật vào DB đang chạy: đặt RESTORE_TARGET=live — sẽ XOÁ dữ liệu hiện tại của các đối tượng trong dump
#  (pg_restore --clean --if-exists). Hãy dừng app trước:  docker compose ... stop app edge
# =============================================
set -eu

dump="${1:?Cach dung: restore.sh <duong-dan-file.dump>}"
[ -f "$dump" ] || { echo "Khong thay file $dump" >&2; exit 1; }
: "${PGHOST:=postgres}"
target="${RESTORE_TARGET:-check}"

if [ "$target" = "live" ]; then
  echo "KHOI PHUC VAO DB DANG CHAY: ${PGDATABASE} (du lieu hien tai bi ghi de)"
  pg_restore --clean --if-exists --no-owner --dbname="$PGDATABASE" "$dump"
  echo "Xong. Khoi dong lai: docker compose ... up -d"
  exit 0
fi

check_db="${PGDATABASE}_restore_check"
echo "Khoi phuc thu vao DB tam ${check_db}"
psql -d postgres -v ON_ERROR_STOP=1 -c "DROP DATABASE IF EXISTS \"${check_db}\"" -c "CREATE DATABASE \"${check_db}\""
pg_restore --no-owner --exit-on-error --dbname="$check_db" "$dump"
echo "--- So dong cua mot so bang chinh ---"
psql -d "$check_db" -At -c "SELECT 'users', count(*) FROM users UNION ALL SELECT 'urls', count(*) FROM urls UNION ALL SELECT 'flyway_schema_history', count(*) FROM flyway_schema_history"
psql -d postgres -c "DROP DATABASE \"${check_db}\""
echo "OK: ban sao luu khoi phuc duoc."
