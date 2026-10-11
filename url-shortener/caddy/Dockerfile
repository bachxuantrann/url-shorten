# =============================================
#  Caddy cho production: reverse proxy + file tĩnh của UI + rate limit.
#
#  Context build là THƯ MỤC GỐC git (url-shorten/), vì cần cả frontend/ và url-shortener/caddy/:
#    docker build -f url-shortener/caddy/Dockerfile .
#  (docker-compose.prod.yml đã cấu hình sẵn.)
#
#  Stage 1: build UI (Vite)
#  Stage 2: build Caddy kèm plugin rate limit bằng xcaddy (Caddy không có rate limit mặc định)
#  Stage 3: image chạy, chỉ gồm binary Caddy + file tĩnh + Caddyfile
#
#  Phiên bản ghim cố định để build lặp lại được. Plugin chưa có bản phát hành mới nên ghim theo commit.
# =============================================

# ===== STAGE 1: UI =====
FROM node:22-alpine AS ui
WORKDIR /ui
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

# ===== STAGE 2: Caddy + rate limit =====
FROM caddy:2.11.7-builder AS caddy-build
RUN xcaddy build v2.11.7 \
    --with github.com/mholt/caddy-ratelimit@5625512f24f6f59d6f64fb3aafe5eecff0b286db

# ===== STAGE 3: RUN =====
FROM caddy:2.11.7-alpine AS runtime
COPY --from=caddy-build /usr/bin/caddy /usr/bin/caddy
COPY --from=ui /ui/dist /srv
COPY url-shortener/caddy/Caddyfile /etc/caddy/Caddyfile

# Fail ngay lúc build nếu Caddyfile sai cú pháp hoặc thiếu plugin
RUN caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile

EXPOSE 80 443 443/udp
