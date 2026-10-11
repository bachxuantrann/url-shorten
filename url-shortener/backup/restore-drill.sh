#!/bin/sh
# =============================================
#  restore-drill.sh: chứng minh một bản dump KHÔNG chỉ tồn tại mà còn khôi phục được.
#
#  Cách dùng:   ./restore-drill.sh /đường/dẫn/dump-....dump
#
#  Dựng một container Postgres tạm (không publish cổng, không nối mạng của stack prod, không đụng DB thật), khôi phục bản
#  dump vào đó với --exit-on-error (bất kỳ lỗi nào cũng thất bại), in số dòng của các bảng chính và phiên bản Flyway mới
#  nhất để so với DB thật bằng mắt, rồi xoá container. Mã thoát 0 chỉ khi khôi phục sạch và có dữ liệu Flyway + bảng urls.
#
#  Chạy trên máy có Docker (VPS hoặc máy cá nhân sau khi kéo bản dump về). Nên chạy định kỳ (vd mỗi tháng): bản sao lưu
#  chưa từng được khôi phục thì chưa phải bản sao lưu.
#
#  Biến môi trường: POSTGRES_IMAGE (mặc định postgres:16-alpine, nên trùng phiên bản với DB thật)
# =============================================
set -eu

DUMP="${1:?usage: $0 <dump file>}"
IMAGE="${POSTGRES_IMAGE:-postgres:16-alpine}"
NAME="restore-drill-$$"

# Git Bash trên Windows: docker.exe cần đường dẫn dạng C:/... (trên Linux/macOS không có cygpath nên giữ nguyên)
hostpath() { if command -v cygpath > /dev/null 2>&1; then cygpath -m "$1"; else printf "%s" "$1"; fi; }

[ -f "$DUMP" ] || { echo "dump not found: $DUMP" >&2; exit 2; }

cleanup() { docker rm -f "$NAME" > /dev/null 2>&1 || true; }
trap cleanup EXIT

echo "starting scratch database ($IMAGE)..."
docker run -d --name "$NAME" -e POSTGRES_PASSWORD=drill -e POSTGRES_DB=drill "$IMAGE" > /dev/null

# Kiểm tra qua TCP: server khởi tạo tạm thời của image chỉ nghe trên socket, nên TCP chỉ sẵn sàng khi server thật đã chạy
tries=0
until docker exec "$NAME" pg_isready -h 127.0.0.1 -U postgres -d drill > /dev/null 2>&1; do
    tries=$((tries + 1))
    [ "$tries" -gt 60 ] && { echo "scratch database did not start" >&2; exit 1; }
    sleep 1
done

docker cp "$(hostpath "$DUMP")" "$NAME:/tmp/restore.dump"
echo "restoring $(basename "$DUMP")..."
docker exec "$NAME" pg_restore -U postgres -d drill --no-owner --exit-on-error /tmp/restore.dump

q() { docker exec "$NAME" psql -U postgres -d drill -Atc "$1"; }

flyway_rows="$(q "select count(*) from flyway_schema_history")"
flyway_latest="$(q "select coalesce(max(version::int), 0) from flyway_schema_history where success")"
has_urls="$(q "select to_regclass('public.urls') is not null")"

echo "--- restore report"
echo "flyway migrations applied: $flyway_rows (latest version: $flyway_latest)"
for t in users urls url_clicks url_daily_stats blocked_domains; do
    if [ "$(q "select to_regclass('public.$t') is not null")" = "t" ]; then
        echo "rows in $t: $(q "select count(*) from $t")"
    fi
done

if [ "$flyway_rows" -lt 1 ] || [ "$has_urls" != "t" ]; then
    echo "RESTORE DRILL FAILED: no Flyway history or no urls table" >&2
    exit 1
fi
echo "RESTORE DRILL OK"
