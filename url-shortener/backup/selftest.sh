#!/bin/sh
# =============================================
#  selftest.sh: kiểm thử backup.sh và restore-drill.sh trên Postgres thật (cần Docker; không đụng stack dev/prod).
#
#  Dựng một Postgres tạm có schema giống app (bảng chia partition, lịch sử Flyway), rồi kiểm:
#   - sao lưu tạo file đã được kiểm tra; xoay vòng giữ đúng số bản; bản mới nhất không bao giờ bị xoá
#   - lỗi (sai DB, DB không phải của app) thất bại, không để file dở dang, không làm hỏng bản cũ
#   - restore drill khôi phục đúng số dòng, và THẤT BẠI với bản dump bị cắt cụt
#   - công thức tính giờ chạy kế tiếp
#  Cách dùng: ./selftest.sh        (mã thoát 0 = tất cả đạt)
# =============================================
set -eu
export MSYS_NO_PATHCONV=1   # Git Bash trên Windows không được đổi /backups thành đường dẫn Windows

HERE="$(cd "$(dirname "$0")" && pwd)"
SRC="backup-selftest-src-$$"
WORK="$(mktemp -d)"
PASS=0; FAIL=0

# Git Bash trên Windows: docker.exe cần đường dẫn dạng C:/... (trên Linux/macOS không có cygpath nên giữ nguyên)
hostpath() { if command -v cygpath > /dev/null 2>&1; then cygpath -m "$1"; else printf "%s" "$1"; fi; }

cleanup() { docker rm -f "$SRC" > /dev/null 2>&1 || true; rm -rf "$WORK"; }
trap cleanup EXIT

check() { # check <mô tả> <lệnh kiểm tra>
    desc="$1"; shift
    if "$@" > /dev/null 2>&1; then PASS=$((PASS + 1)); echo "ok   - $desc"; else FAIL=$((FAIL + 1)); echo "FAIL - $desc"; fi
}
sq() { docker exec "$SRC" psql -U postgres -d appdb -Atc "$1"; }
inbox() { docker exec "$SRC" sh -c "$1"; }
# Chạy backup.sh trong container nguồn (đã có pg_dump/pg_restore, kết nối qua socket)
run_backup() { docker exec -e PGUSER=postgres -e PGDATABASE="${1:-appdb}" -e KEEP_DAILY=3 -e KEEP_WEEKLY=2 "$SRC" /tmp/backup.sh once; }

echo "starting source database..."
docker run -d --name "$SRC" -e POSTGRES_PASSWORD=x -e POSTGRES_DB=appdb postgres:16-alpine > /dev/null
until docker exec "$SRC" pg_isready -h 127.0.0.1 -U postgres -d appdb > /dev/null 2>&1; do sleep 1; done
docker cp "$(hostpath "$HERE/backup.sh")" "$SRC:/tmp/backup.sh"
docker exec "$SRC" chmod +x /tmp/backup.sh
docker exec "$SRC" createdb -U postgres emptydb

sq "
create table flyway_schema_history (installed_rank int primary key, version text, success boolean);
insert into flyway_schema_history values (1,'1',true),(2,'2',true),(9,'9',true);
create table users (id serial primary key, name text);
insert into users (name) select 'u' || g from generate_series(1,3) g;
create table urls (id serial primary key, short_code text);
insert into urls (short_code) select 'c' || g from generate_series(1,5) g;
create table url_clicks (id bigint, clicked_at timestamp not null) partition by range (clicked_at);
create table url_clicks_y2026m10 partition of url_clicks for values from ('2026-10-01') to ('2026-11-01');
create table url_clicks_default partition of url_clicks default;
insert into url_clicks select g, '2026-10-05'::timestamp + (g || ' minutes')::interval from generate_series(1,30) g;
insert into url_clicks select 100+g, '2025-01-01'::timestamp from generate_series(1,10) g;
"

# ---------- sao lưu cơ bản ----------
check "backup succeeds" run_backup
latest="$(inbox 'ls -1 /backups/daily | sort | tail -1')"
check "dump file is created in daily/" test -n "$latest"
check "no partial files are left" sh -c "! docker exec $SRC sh -c 'ls /backups/daily | grep -E \"partial|toc\"'"
check ".last_success marker exists" docker exec "$SRC" test -f /backups/.last_success
if [ "$(date -u +%u)" = "7" ]; then
    check "Sunday backup is copied to weekly/" sh -c "[ \$(docker exec $SRC ls /backups/weekly | wc -l) -ge 1 ]"
else
    check "weekday backup is not copied to weekly/" sh -c "[ \$(docker exec $SRC ls /backups/weekly | wc -l) -eq 0 ]"
fi

# ---------- xoay vòng ----------
inbox 'for d in 01 02 03 04 05 06; do : > /backups/daily/dump-2026-01-${d}_000000.dump; done'
inbox ': > /backups/weekly/dump-2025-12-01_000000.dump; : > /backups/weekly/dump-2025-12-08_000000.dump; : > /backups/weekly/dump-2025-12-15_000000.dump'
run_backup > /dev/null
check "daily retention keeps exactly KEEP_DAILY files" sh -c "[ \$(docker exec $SRC ls /backups/daily | wc -l) -eq 3 ]"
newest="$(inbox 'ls -1 /backups/daily | sort | tail -1')"
check "the newest backup survives pruning" sh -c "[ '$newest' != 'dump-2026-01-06_000000.dump' ]"
check "oldest fake backups were removed" sh -c "! docker exec $SRC test -f /backups/daily/dump-2026-01-01_000000.dump"
check "weekly retention keeps at most KEEP_WEEKLY files" sh -c "[ \$(docker exec $SRC ls /backups/weekly | wc -l) -le 2 ]"

# ---------- lỗi ----------
before="$(inbox 'ls -1 /backups/daily | sort')"
marker_before="$(inbox 'cat /backups/.last_success')"
check "backup of a missing database fails" sh -c "! docker exec -e PGUSER=postgres -e PGDATABASE=nope $SRC /tmp/backup.sh once"
check "backup of a non-app database (no flyway history) fails verification" sh -c "! docker exec -e PGUSER=postgres -e PGDATABASE=emptydb $SRC /tmp/backup.sh once"
after="$(inbox 'ls -1 /backups/daily | sort')"
check "failed runs leave existing backups untouched and no partial files" test "$before" = "$after"
check ".last_success is not advanced by a failed run" sh -c "[ '$(inbox 'cat /backups/.last_success')' = '$marker_before' ]"

# ---------- restore drill ----------
good="$(inbox 'ls -1 /backups/daily | sort | tail -1')"
docker cp "$SRC:/backups/daily/$good" "$(hostpath "$WORK")/good.dump"
if sh "$HERE/restore-drill.sh" "$WORK/good.dump" > "$WORK/drill.out" 2>&1; then PASS=$((PASS + 1)); echo "ok   - restore drill succeeds on a good dump"; else FAIL=$((FAIL + 1)); echo "FAIL - restore drill succeeds on a good dump"; cat "$WORK/drill.out"; fi
check "drill restored the same users count" grep -q "rows in users: 3" "$WORK/drill.out"
check "drill restored the same urls count" grep -q "rows in urls: 5" "$WORK/drill.out"
check "drill restored partitioned clicks (all partitions)" grep -q "rows in url_clicks: 40" "$WORK/drill.out"
check "drill reports the latest Flyway version" grep -q "latest version: 9" "$WORK/drill.out"

head -c "$(( $(wc -c < "$WORK/good.dump") / 2 ))" "$WORK/good.dump" > "$WORK/truncated.dump"
check "restore drill FAILS on a truncated dump" sh -c "! sh '$HERE/restore-drill.sh' '$WORK/truncated.dump'"
check "restore drill FAILS when the file does not exist" sh -c "! sh '$HERE/restore-drill.sh' '$WORK/missing.dump'"
check "drill leaves no scratch containers behind" sh -c "[ -z \"\$(docker ps -aq --filter name=restore-drill-)\" ]"

# ---------- lịch chạy ----------
sed -n '/^num()/,/^}/p' "$HERE/backup.sh" > "$WORK/fns.sh"
sched() { BACKUP_AT="$1" sh -c ". '$WORK/fns.sh'; seconds_until_next"; }
check "schedule: any time is within (0, 86400]" sh -c "s=\$(BACKUP_AT=00:00 sh -c \". '$WORK/fns.sh'; seconds_until_next\"); [ \$s -gt 0 ] && [ \$s -le 86400 ]"
in2="$(date -u -d '+2 minutes' '+%H:%M' 2>/dev/null || date -u -v+2M '+%H:%M')"
s="$(sched "$in2")"
check "schedule: a time 2 minutes ahead waits about 2 minutes (got ${s}s)" sh -c "[ $s -ge 60 ] && [ $s -le 180 ]"
s1="$(sched 08:08)"; s2="$(sched 08:09)"
check "schedule: 08:08 and 08:09 are one minute apart (zero-padded values are not read as octal)" test "$(( (s2 - s1 + 86400) % 86400 ))" -eq 60

# Chế độ loop chạy được dưới busybox sh (shell thật của container): in lịch rồi ngủ chờ tới giờ
loop_out="$(docker exec -e PGUSER=postgres -e PGDATABASE=appdb "$SRC" timeout 3 /tmp/backup.sh loop 2>&1 || true)"
check "loop mode starts under busybox sh and announces the next run" sh -c "echo '$loop_out' | grep -q 'next backup in'"

echo "--- $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
