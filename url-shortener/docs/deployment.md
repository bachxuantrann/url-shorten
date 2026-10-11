# Triển khai production (Phase 9)

Kiến trúc trên một máy chủ Docker:

```
Internet ──80/443──> edge (Caddy: HTTPS tự động + rate limit + giao diện web)
                       ├─ /api/*, /{code} ──> app (Spring Boot, không mở cổng ra host)
                       └─ /, /assets/* ...  ──> file tĩnh của frontend (nướng sẵn trong image edge)
                     app ──> postgres, redis, kafka (chỉ trong mạng nội bộ)
                     backup ──> pg_dump định kỳ vào volume postgres_backups
```

Chỉ `edge` publish cổng (80, 443, 443/udp). `/actuator/*` bị Caddy chặn (healthcheck của Docker gọi thẳng vào app).

## 1. Chuẩn bị máy chủ (một lần)

1. VPS Linux có Docker Engine + plugin Compose (v2.24 trở lên), mở cổng 80 và 443.
2. DNS: bản ghi `A`/`AAAA` của domain trỏ về IP máy chủ **trước** lần chạy đầu (Caddy cần xác thực Let's Encrypt).
3. Tạo user `deploy` (thuộc nhóm `docker`), thư mục triển khai, ví dụ `/opt/url-shortener`.
4. Tạo cặp khoá SSH chỉ dùng cho pipeline; đặt khoá công khai vào `~deploy/.ssh/authorized_keys`.
5. Tạo `.env.prod` **trong thư mục triển khai** từ `.env.prod.example` và điền giá trị thật (xem bảng dưới).
   `chmod 600 .env.prod`. File này không bao giờ đi qua GitHub.

| Biến | Cách tạo / lưu ý |
|---|---|
| `APP_DOMAIN`, `ACME_EMAIL` | domain (không `https://`) và email nhận thông báo chứng chỉ |
| `APP_BASE_URL` | `https://<APP_DOMAIN>` |
| `SHORT_CODE_SECRET` | `openssl rand -hex 32`. **Không bao giờ đổi sau khi có link** (đổi là mã mới trùng/khác mã cũ) |
| `CLICK_VISITOR_SECRET` | `openssl rand -hex 32` (đổi chỉ làm đứt quãng đếm unique visitor một lần) |
| `JWT_SECRET` | `openssl rand -hex 64` (đổi = mọi người phải đăng nhập lại) |
| `DB_PASSWORD`, `REDIS_PASSWORD` | mật khẩu mạnh |
| `KAFKA_CLUSTER_ID` | `docker run --rm confluentinc/cp-kafka:7.6.0 kafka-storage random-uuid` |
| `ADMIN_*` | tài khoản admin tạo lúc app khởi động lần đầu |

`deploy/deploy.sh` từ chối chạy nếu `.env.prod` còn chuỗi `CHANGE_ME`.

## 2. Pipeline (GitHub Actions)

`.github/workflows/deploy.yml`: sau khi workflow **CI** xanh trên `main` (hoặc chạy tay bằng *Run workflow*):

1. build image `app` và `edge` (Caddy + frontend), đẩy lên GHCR với tag là SHA commit và `latest`;
2. chép `docker-compose.prod.yml`, `deploy/`, `backup/` lên máy chủ;
3. SSH chạy `deploy/deploy.sh`: ghi `APP_IMAGE`/`EDGE_IMAGE` vào `.env.prod`, `pull`, `up -d`, **đợi app healthy**;
   nếu không healthy sau 240 s thì tự quay về image trước và để job đỏ.

Thiết lập trên GitHub: *Settings → Environments → New environment* tên **`production`** (nên bật *Required reviewers*
để có bước duyệt tay trước khi deploy), rồi thêm:

| Loại | Tên | Giá trị |
|---|---|---|
| Secret | `DEPLOY_SSH_KEY` | khoá riêng SSH của bước 1.4 |
| Secret | `DEPLOY_KNOWN_HOSTS` | kết quả `ssh-keyscan -t ed25519 <host>` (ghim host key, không tắt kiểm tra) |
| Variable | `DEPLOY_HOST`, `DEPLOY_USER`, `DEPLOY_PATH` | host, user, thư mục triển khai |

Giới hạn cần biết:

- **Rollback không hoàn tác schema.** Flyway chạy lúc app khởi động, nên migration phải tương thích ngược một phiên bản
  (thêm trước, xoá sau), nếu không bản cũ sẽ không chạy được trên schema mới.
- Quay về bản cũ thủ công: sửa `APP_IMAGE`/`EDGE_IMAGE` trong `.env.prod` thành tag SHA cũ rồi
  `docker compose -f docker-compose.prod.yml --env-file .env.prod up -d`.
- Lần đầu tiên không có bản trước để rollback; nếu lỗi thì xem log: `docker compose ... logs app`.

## 3. Giao diện web

`frontend/` (React + Vite + TypeScript). Dùng **HashRouter**: mọi màn hình nằm dưới `/#/...` nên không chiếm đường dẫn nào ở
gốc domain (mỗi đường dẫn một đoạn là short code). Nhờ vậy không phải thêm từ khoá vào `ShortCodes.RESERVED`.
Gồm: trang chủ (rút gọn nhanh ẩn danh), đăng ký/đăng nhập, quản lý link (tạo với alias/mật khẩu/hạn, tìm kiếm, lọc trạng thái,
phân trang, sửa, bật/tắt, xoá, đặt/gỡ mật khẩu, mã QR).

Chạy khi phát triển (app Spring chạy ở :8080):

```bash
cd frontend && npm ci && npm run dev      # http://localhost:5173, /api được proxy sang :8080
npm run build                              # tsc --noEmit + vite build -> dist/
npm test                                   # vitest
```

Token: access token chỉ nằm trong bộ nhớ trang; refresh token nằm ở `localStorage` để giữ đăng nhập. Đánh đổi: một lỗ XSS
đọc được refresh token. Giảm thiểu bằng CSP nghiêm ngặt của Caddy (`script-src 'self'`, không inline). Nếu cần an toàn hơn,
chuyển sang cookie `HttpOnly` cần đổi backend (CSRF, SameSite) nên chưa làm.

**Chưa có trang nhập mật khẩu cho link bảo vệ.** Mở thẳng link có mật khẩu trong trình duyệt vẫn nhận JSON `403 ERR_408`;
API `POST /api/v1/public/urls/{code}/unlock` đã sẵn sàng để làm trang đó sau.

## 4. HTTPS, CORS, rate limit

- **HTTPS:** Caddy tự xin/gia hạn chứng chỉ, tự chuyển HTTP -> HTTPS, thêm HSTS (1 năm, không `includeSubDomains`) và các header
  bảo mật (CSP, `X-Frame-Options`, `nosniff`, `Referrer-Policy`, `Permissions-Policy`). Chứng chỉ ở volume `caddy_data`.
- **CORS:** không bật. Giao diện và API cùng origin nên không cần; trình duyệt từ origin khác bị chặn theo mặc định.
  Nếu sau này tách frontend sang domain khác thì phải thêm cấu hình CORS có danh sách origin rõ ràng.
- **Rate limit (Caddy, theo IP):** `/api/v1/auth/*` 20/phút, `/api/v1/public/*` 60/phút, `/api/*` 300/phút, redirect `/{code}`
  600/phút; vượt thì `429` + `Retry-After`. Đây là lớp thô ở rìa, **bổ sung** cho rate limit nghiệp vụ trong Redis của app (tạo link,
  mở khoá). Plugin `mholt/caddy-ratelimit` nên image edge build bằng `xcaddy`.
  Nếu đặt Cloudflare/load balancer phía trước Caddy thì mọi request có cùng IP: phải đổi `key` trong `edge/Caddyfile` sang
  header IP client của dịch vụ đó (và chỉ tin header đó từ dải IP của dịch vụ).
- App: `shutdown: graceful`, `show-sql: false`, `ddl-auto: validate`, `baseline-on-migrate: false`, `clean-disabled: true`,
  mọi bí mật bắt buộc qua biến môi trường (không có mặc định trong `application-prod.yaml`).

## 5. Sao lưu Postgres

Service `backup` chạy `backup/backup.sh`: mỗi `BACKUP_INTERVAL_SECONDS` (1 ngày) `pg_dump -Fc` vào volume `postgres_backups`,
kiểm tra file đọc được bằng `pg_restore --list`, giữ `BACKUP_RETENTION_DAYS` (14) ngày. Backup lỗi thì ghi log `ERROR` và thử lại
chu kỳ sau; nên theo dõi log: `docker compose ... logs backup`.

**Kiểm tra khôi phục (nên làm định kỳ — backup chưa thử restore thì chưa phải backup):**

```bash
docker compose -f docker-compose.prod.yml --env-file .env.prod exec backup ls -l /backups
docker compose -f docker-compose.prod.yml --env-file .env.prod exec backup \
  sh /scripts/restore.sh /backups/<file>.dump        # restore vào DB tạm, đếm dòng, rồi xoá DB tạm
```

**Khôi phục thật** (ghi đè dữ liệu hiện tại): `docker compose ... stop app edge`, rồi
`... exec -e RESTORE_TARGET=live backup sh /scripts/restore.sh /backups/<file>.dump`, rồi `docker compose ... up -d`.
Redis chỉ là cache/phiên: sau khi restore có thể `docker compose ... exec redis redis-cli -a $REDIS_PASSWORD flushall`
để bỏ cache link cũ (người dùng phải đăng nhập lại).

**Điều chưa làm:** backup nằm **cùng máy chủ** với DB nên chỉ chống được lỗi vận hành/xoá nhầm, không chống được mất máy chủ.
Đẩy ra ngoài (S3-compatible) được hoãn theo quyết định của chủ dự án. Trước mắt hãy sao chép định kỳ về máy khác, ví dụ cron trên
máy khác: `rsync -a deploy@host:/var/lib/docker/volumes/url-shortener-prod_postgres_backups/_data/ ./backups/`.
Kafka, Redis và `caddy_data` không nằm trong backup (click đang chờ trong Kafka và cache có thể mất khi mất máy).

## 6. Chưa được kiểm chứng

Môi trường phát triển không có Docker daemon và không có VPS, nên các phần sau **chưa chạy thật**: build các image
(`Dockerfile`, `edge/Dockerfile`), `docker compose up` của stack prod, xin chứng chỉ Let's Encrypt, workflow GitHub Actions,
và `backup` trong container. Đã kiểm chứng riêng: `Caddyfile` (build Caddy bằng xcaddy kèm plugin, `caddy validate`, chạy thật
với upstream giả: file tĩnh, chặn actuator, header bảo mật, 429 + `Retry-After` ở request thứ 21), `backup.sh`/`restore.sh`
(Postgres thật: dump, retention, restore thử, restore thật, lỗi không để lại file dở), `deploy.sh` (docker giả: thành công,
rollback, chặn `CHANGE_ME`), `docker compose config`, cú pháp YAML của workflow, build + test của frontend.
