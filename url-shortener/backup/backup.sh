#!/bin/sh
# =============================================
#  backup.sh: sao lưu PostgreSQL bằng pg_dump (định dạng custom) kèm xoay vòng và kiểm tra bản dump.
#
#  Chế độ:
#    backup.sh once   chạy một lần rồi thoát (mã thoát khác 0 nếu thất bại)
#    backup.sh loop   chạy mỗi ngày lúc BACKUP_AT (mặc định 03:15, giờ của container = UTC); lỗi chỉ ghi log và thử lại hôm sau
#
#  Biến môi trường:
#    PGHOST PGPORT PGUSER PGPASSWORD PGDATABASE   kết nối DB (chuẩn của libpq)
#    BACKUP_DIR     thư mục đích (mặc định /backups): daily/, weekly/ và file .last_success
#    KEEP_DAILY     số bản hằng ngày giữ lại (mặc định 7)
#    KEEP_WEEKLY    số bản hằng tuần giữ lại (mặc định 4); bản Chủ nhật được sao thêm sang weekly/
#    BACKUP_AT      HH:MM (chế độ loop)
#
#  Một bản dump chỉ được giữ lại khi `pg_restore --list` đọc được và có bảng flyway_schema_history; bản hỏng bị xoá và
#  lần chạy bị coi là thất bại, không bao giờ để lại file dở dang làm xoay vòng xoá mất bản tốt.
#  File tên theo thời gian (dump-YYYY-MM-DD_HHMMSS.dump) nên xoay vòng sắp xếp theo tên, không phụ thuộc mtime.
# =============================================
set -eu

BACKUP_DIR="${BACKUP_DIR:-/backups}"
KEEP_DAILY="${KEEP_DAILY:-7}"
KEEP_WEEKLY="${KEEP_WEEKLY:-4}"
BACKUP_AT="${BACKUP_AT:-03:15}"

log() { printf '%s backup: %s\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" "$*"; }

# Giữ $2 file mới nhất (theo tên) trong thư mục $1, xoá phần còn lại
prune() {
    dir="$1"; keep="$2"
    ls -1 "$dir" 2>/dev/null | grep -E '^dump-.*\.dump$' | sort -r | tail -n +"$((keep + 1))" | while read -r f; do
        log "removing old backup $dir/$f"
        rm -f "$dir/$f"
    done
}

run_backup() {
    mkdir -p "$BACKUP_DIR/daily" "$BACKUP_DIR/weekly"
    stamp="$(date -u '+%Y-%m-%d_%H%M%S')"
    final="$BACKUP_DIR/daily/dump-$stamp.dump"
    partial="$final.partial"

    log "dumping database '${PGDATABASE}' from '${PGHOST:-localhost}'"
    if ! pg_dump --format=custom --no-owner --no-privileges --file="$partial"; then
        rm -f "$partial"
        log "ERROR: pg_dump failed"
        return 1
    fi

    # Kiểm tra bản dump đọc được và có lịch sử Flyway (thiếu thì chắc chắn không phải DB của app)
    if ! pg_restore --list "$partial" > "$partial.toc" 2>/dev/null || ! grep -q 'flyway_schema_history' "$partial.toc"; then
        rm -f "$partial" "$partial.toc"
        log "ERROR: dump failed verification (unreadable or no flyway_schema_history)"
        return 1
    fi
    rm -f "$partial.toc"
    mv "$partial" "$final"
    log "created $final ($(wc -c < "$final") bytes)"

    # Chủ nhật: giữ thêm một bản hằng tuần
    if [ "$(date -u +%u)" = "7" ]; then
        cp "$final" "$BACKUP_DIR/weekly/"
        log "weekly copy created"
    fi

    prune "$BACKUP_DIR/daily" "$KEEP_DAILY"
    prune "$BACKUP_DIR/weekly" "$KEEP_WEEKLY"
    date -u '+%Y-%m-%dT%H:%M:%SZ' > "$BACKUP_DIR/.last_success"
}

# Chuỗi 2 chữ số ("08") thành số thập phân: "1" + "08" = 108, trừ 100 (tránh bị hiểu là bát phân)
num() { echo "$((1$1 - 100))"; }

# Số giây từ bây giờ tới lần BACKUP_AT kế tiếp (chỉ dùng phép tính số học, busybox date không hỗ trợ đủ cú pháp)
seconds_until_next() {
    target=$(( $(num "${BACKUP_AT%%:*}") * 3600 + $(num "${BACKUP_AT##*:}") * 60 ))
    now=$(( $(num "$(date -u +%H)") * 3600 + $(num "$(date -u +%M)") * 60 + $(num "$(date -u +%S)") ))
    diff=$(( target - now ))
    [ "$diff" -le 0 ] && diff=$(( diff + 86400 ))
    echo "$diff"
}

case "${1:-once}" in
    once)
        run_backup
        ;;
    loop)
        log "scheduled daily at $BACKUP_AT UTC, keeping $KEEP_DAILY daily + $KEEP_WEEKLY weekly"
        while true; do
            wait_s="$(seconds_until_next)"
            log "next backup in ${wait_s}s"
            sleep "$wait_s"
            run_backup || log "backup failed, will retry at the next scheduled time"
        done
        ;;
    *)
        echo "usage: $0 once|loop" >&2
        exit 2
        ;;
esac
