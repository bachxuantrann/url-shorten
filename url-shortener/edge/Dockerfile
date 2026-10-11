# =============================================
#  Image "edge": Caddy (có plugin rate limit) + giao diện web đã build.
#  Build context phải là thư mục url-shortener/ (cần cả frontend/ và edge/).
#
#  Stage 1: build frontend (Vite)
#  Stage 2: build Caddy bằng xcaddy kèm plugin mholt/caddy-ratelimit
#  Stage 3: image chạy
# =============================================

# ===== STAGE 1: FRONTEND =====
FROM node:22-alpine AS web
WORKDIR /web
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

# ===== STAGE 2: CADDY + PLUGIN =====
FROM caddy:2.10-builder AS caddy
RUN xcaddy build --with github.com/mholt/caddy-ratelimit

# ===== STAGE 3: RUNTIME =====
FROM caddy:2.10-alpine
COPY --from=caddy /usr/bin/caddy /usr/bin/caddy
COPY edge/Caddyfile /etc/caddy/Caddyfile
COPY --from=web /web/dist /srv/www
# Kiểm tra cú pháp ngay lúc build image: lỗi cấu hình làm hỏng build, không để lọt lên server
RUN APP_DOMAIN=example.com ACME_EMAIL=ops@example.com caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile
