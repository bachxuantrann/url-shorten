#!/bin/sh
# =============================================
#  Sao lưu Postgres định kỳ (chạy trong container `backup` của docker-compose.prod.yml).
#
#  Mỗi BACKUP_INTERVAL_SECONDS (mặc định 86400 = 1 ngày) chạy pg_dump định dạng custom (-Fc, đã nén,
#  restore chọn lọc được) vào /backups/<db>_<UTC timestamp>.dump, rồi xoá bản cũ hơn BACKUP_RETENTION_DAYS.
#  Bản dump được ghi ra file .partial và chỉ đổi tên sau khi pg_restore --list đọc được, nên không bao giờ
#  có file dump dở dang trông như bản hợp lệ. Backup lỗi thì GHI LOG VÀ thử lại ở chu kỳ sau, không thoát.
#
#  Backup nằm cùng máy chủ với DB: chỉ bảo vệ khỏi lỗi vận hành/xoá nhầm, KHÔNG bảo vệ khi mất máy chủ.
#  Hãy sao chép thư mục này ra ngoài máy (hoặc thêm bước đẩy S3 sau này). Xem docs/deployment.md.
# =============================================
set -u

: "${PGHOST:=postgres}"
: "${BACKUP_DIR:=/backups}"
: "${BACKUP_INTERVAL_SECONDS:=86400}"
: "${BACKUP_RETENTION_DAYS:=14}"
: "${BACKUP_RUN_ONCE:=false}"   # true = chạy một lần rồi thoát (dùng để test / chạy tay)

log() { echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) backup: $*"; }

run_backup() {
  stamp=$(date -u +%Y%m%dT%H%M%SZ)
  final="${BACKUP_DIR}/${PGDATABASE}_${stamp}.dump"
  part="${final}.partial"

  if ! pg_dump -Fc --no-owner --file="$part"; then
    log "ERROR pg_dump that bai"; rm -f "$part"; return 1
  fi
  if ! pg_restore --list "$part" >/dev/null 2>&1; then
    log "ERROR file dump khong doc duoc, bo qua"; rm -f "$part"; return 1
  fi
  mv "$part" "$final"
  log "OK $(basename "$final") ($(wc -c <"$final") bytes)"

  # Xoá bản cũ và file .partial mồ côi (do container bị dừng giữa chừng)
  find "$BACKUP_DIR" -maxdepth 1 -name "${PGDATABASE}_*.dump" -mtime "+${BACKUP_RETENTION_DAYS}" -print -delete \
    | while read -r f; do log "da xoa ban cu $(basename "$f")"; done
  find "$BACKUP_DIR" -maxdepth 1 -name '*.partial' -mmin +60 -delete
  return 0
}

mkdir -p "$BACKUP_DIR"
while true; do
  run_backup
  rc=$?
  if [ "$BACKUP_RUN_ONCE" = "true" ]; then exit "$rc"; fi
  sleep "$BACKUP_INTERVAL_SECONDS"
done
