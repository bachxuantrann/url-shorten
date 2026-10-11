#!/bin/sh
# =============================================
#  Chạy TRÊN MÁY CHỦ (pipeline gọi qua SSH): kéo image mới và khởi động lại stack production.
#
#  Dùng:  deploy/deploy.sh <APP_IMAGE> <EDGE_IMAGE>
#  Chạy trong thư mục chứa docker-compose.prod.yml và .env.prod (thư mục triển khai trên máy chủ).
#
#  - Ghi APP_IMAGE / EDGE_IMAGE vào .env.prod để lần `docker compose up -d` thủ công sau này vẫn dùng đúng bản.
#  - Đợi app healthy (tối đa DEPLOY_WAIT_SECONDS, mặc định 240s). Nếu không healthy thì TỰ ĐỘNG quay về image
#    trước đó (rollback) và thoát với mã lỗi, để pipeline đỏ.
#  - Migration Flyway chạy lúc app khởi động: rollback image KHÔNG hoàn tác schema. Migration phải tương thích
#    ngược một phiên bản (thêm cột/bảng trước, xoá sau).
# =============================================
set -eu

APP_IMAGE_NEW="${1:?Cach dung: deploy.sh <APP_IMAGE> <EDGE_IMAGE>}"
EDGE_IMAGE_NEW="${2:?Cach dung: deploy.sh <APP_IMAGE> <EDGE_IMAGE>}"
ENV_FILE="${ENV_FILE:-.env.prod}"
COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.prod.yml}"
WAIT="${DEPLOY_WAIT_SECONDS:-240}"

[ -f "$ENV_FILE" ] || { echo "Thieu $ENV_FILE" >&2; exit 1; }
if grep -q 'CHANGE_ME' "$ENV_FILE"; then
  echo "$ENV_FILE van con gia tri CHANGE_ME_*: dien gia tri that truoc khi deploy" >&2
  exit 1
fi

compose() { docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" "$@"; }

get_var() { sed -n "s/^$1=//p" "$ENV_FILE" | tail -n 1; }

# Ghi KEY=VALUE vào env file: thay dòng cũ nếu có, không thì thêm cuối file
set_var() {
  if grep -q "^$1=" "$ENV_FILE"; then
    sed "s#^$1=.*#$1=$2#" "$ENV_FILE" > "$ENV_FILE.tmp" && cat "$ENV_FILE.tmp" > "$ENV_FILE" && rm -f "$ENV_FILE.tmp"
  else
    printf '%s=%s\n' "$1" "$2" >> "$ENV_FILE"
  fi
}

wait_healthy() {
  end=$(( $(date +%s) + WAIT ))
  while [ "$(date +%s)" -lt "$end" ]; do
    status=$(docker inspect --format '{{.State.Health.Status}}' url_shortener_app 2>/dev/null || echo missing)
    [ "$status" = "healthy" ] && return 0
    sleep 5
  done
  return 1
}

PREV_APP=$(get_var APP_IMAGE)
PREV_EDGE=$(get_var EDGE_IMAGE)

set_var APP_IMAGE "$APP_IMAGE_NEW"
set_var EDGE_IMAGE "$EDGE_IMAGE_NEW"

echo "Keo image: $APP_IMAGE_NEW , $EDGE_IMAGE_NEW"
compose pull app edge
compose up -d --remove-orphans

if wait_healthy; then
  echo "Deploy OK"
  docker image prune -f >/dev/null 2>&1 || true
  exit 0
fi

echo "App khong healthy sau ${WAIT}s. Log app:" >&2
compose logs --tail 80 app >&2 || true

if [ -n "$PREV_APP" ] && [ -n "$PREV_EDGE" ]; then
  echo "Rollback ve $PREV_APP" >&2
  set_var APP_IMAGE "$PREV_APP"
  set_var EDGE_IMAGE "$PREV_EDGE"
  compose up -d --remove-orphans || true
  wait_healthy && echo "Da rollback, he thong chay lai ban cu" >&2 || echo "Rollback cung khong healthy: can xu ly tay" >&2
else
  echo "Khong co ban truoc de rollback (lan deploy dau tien)" >&2
fi
exit 1
