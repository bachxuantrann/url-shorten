# Triển khai (Phase 9)

Hướng dẫn đưa hệ thống lên một VPS bằng Docker Compose, cách phát hành bằng GitHub Actions, và cách sao lưu / khôi phục
PostgreSQL. Cuối tài liệu có danh sách những gì **đã** và **chưa** được kiểm chứng.

## 1. Kiến trúc khi chạy

```
internet ──80/443──> Caddy ──(mạng Docker riêng)──> app ──> postgres, redis, kafka
                       │                                      ^
                       └─ file tĩnh của UI (/srv)             └─ backup (pg_dump hằng ngày -> thư mục của host)
```

- **Chỉ Caddy mở cổng ra host** (80/443). `app`, `postgres`, `redis`, `kafka` chỉ có trong mạng Docker.
- Caddy làm HTTPS (Let's Encrypt), phục vụ UI, chuyển `/api/*` và short link sang app, và giới hạn tốc độ theo IP.
  Cấu hình: [`caddy/Caddyfile`](../caddy/Caddyfile). UI và API **cùng origin** nên không có CORS.
- App chỉ tin `X-Forwarded-For` khi kết nối đến từ dải IP riêng (mạng Docker), nên Caddy **phải** nằm cùng mạng Docker
  với app (đã đúng trong compose). Nếu đặt thêm một proxy/CDN phía trước Caddy, xem phần "Cloudflare/LB" ở cuối.

## 2. Chuẩn bị server

| Mục | Khuyến nghị |
|---|---|
| Cấu hình | ≥ 2 vCPU, **4 GB RAM** (JVM + Postgres + Redis + Kafka), đĩa SSD ≥ 40 GB. Chưa đo thực tế, hãy theo dõi bộ nhớ sau khi chạy |
| Hệ điều hành | Linux có Docker Engine + Docker Compose v2 |
| DNS | Bản ghi `A` (và `AAAA` nếu có IPv6) của domain trỏ về IP server **trước khi** chạy lần đầu, nếu không Let's Encrypt thất bại và có thể bị giới hạn số lần thử |
| Tường lửa | Mở 80/tcp, 443/tcp, 443/udp (HTTP/3) và 22/tcp (SSH). Đóng mọi cổng khác |
| User deploy | Một user riêng thuộc nhóm `docker`, đăng nhập bằng khoá SSH, không dùng root |
| Đĩa backup | Thư mục `BACKUP_DIR` trên **đĩa/phân vùng khác** với đĩa chứa volume Docker (xem mục 6) |

## 3. Tạo `.env.prod` trên server (một lần)

`.env.prod` trong repo chỉ là **mẫu** với giá trị `CHANGE_ME_*` (repo công khai). Bản thật được tạo **trực tiếp trên
server**, quyền `chmod 600`, và pipeline **không bao giờ** ghi đè nó. Việc cần làm:

```bash
openssl rand -hex 64   # JWT_SECRET
openssl rand -hex 32   # SHORT_CODE_SECRET, CLICK_VISITOR_SECRET (mỗi cái một giá trị)
docker run --rm confluentinc/cp-kafka:7.6.0 kafka-storage random-uuid   # KAFKA_CLUSTER_ID
```

Các biến bắt buộc phải đặt giá trị thật: `DB_PASSWORD`, `REDIS_PASSWORD`, `KAFKA_CLUSTER_ID`, `JWT_SECRET`,
`ADMIN_USERNAME/PASSWORD/EMAIL`, `APP_BASE_URL` (= `https://<domain>`), `SHORT_CODE_SECRET`, `CLICK_VISITOR_SECRET`,
`SITE_ADDRESS` (domain, không có `https://`), `ACME_EMAIL`, `BACKUP_DIR`.

- **`SHORT_CODE_SECRET` không bao giờ được đổi sau lần deploy đầu**: đổi nó làm bộ sinh mã cấp mã khác đi và có thể
  trùng mã đã phát hành. Đổi `CLICK_VISITOR_SECRET` chỉ làm số khách duy nhất đứt quãng một lần.
- **Sao lưu riêng `.env.prod` ở nơi an toàn** (trình quản lý mật khẩu). Có dump database mà mất `SHORT_CODE_SECRET`
  thì không khôi phục đúng được.
- `APP_BASE_URL` quyết định `shortUrl` và nội dung mã QR: sai domain thì link trong UI trỏ sai.

## 4. Lần deploy đầu tiên (thủ công)

```bash
mkdir -p /opt/url-shortener/backup && cd /opt/url-shortener
# đặt .env.prod (mục 3), docker-compose.prod.yml và backup/backup.sh, backup/restore-drill.sh vào đây
mkdir -p "$BACKUP_DIR"            # đúng giá trị BACKUP_DIR trong .env.prod, trên đĩa khác

# Cách A: build tại chỗ (cần cả repo: compose build từ thư mục url-shortener/ và gốc git cho Caddy)
git clone <repo> src && cd src/url-shortener
docker compose -f docker-compose.prod.yml --env-file /opt/url-shortener/.env.prod up -d --build

# Cách B: dùng image đã build bởi CI (mục 5)
export APP_IMAGE=ghcr.io/<owner>/<repo>/app:v1.0.0 CADDY_IMAGE=ghcr.io/<owner>/<repo>/caddy:v1.0.0
docker compose --env-file .env.prod -f docker-compose.prod.yml pull app caddy
docker compose --env-file .env.prod -f docker-compose.prod.yml up -d --no-build
```

Image trên GHCR mặc định là **private**: trên server chạy một lần `docker login ghcr.io -u <user>` với Personal Access
Token chỉ có quyền `read:packages`, hoặc chuyển package sang public.

Kiểm tra sau khi chạy: `docker compose ps` (tất cả `healthy`), mở `https://<domain>/`, tạo một link ẩn danh, bấm thử,
đăng nhập admin. Chờ ~90 giây cho app khởi động (có `start_period`).

## 5. Phát hành bằng GitHub Actions

[`.github/workflows/release.yml`](../../.github/workflows/release.yml):

1. Đẩy tag `vX.Y.Z` -> chạy lại toàn bộ CI (backend, frontend, script backup, build Caddy). CI đỏ thì dừng.
2. Build và đẩy hai image lên GHCR: `ghcr.io/<owner>/<repo>/app:<tag>` và `.../caddy:<tag>` (Caddy đã kèm UI).
3. **Chỉ khi** biến repo `DEPLOY_ENABLED=true`: gửi `docker-compose.prod.yml` + script backup của tag đó lên server qua
   SSH, `pull`, `up -d`, chờ app `healthy`, rồi gọi thử `https://<domain>/`.

Thiết lập một lần (Settings -> Environments -> `production`; nên bật "Required reviewers" để deploy cần người duyệt):

| Loại | Tên | Giá trị |
|---|---|---|
| Secret | `DEPLOY_SSH_KEY` | khoá riêng SSH của user deploy (`ssh-keygen -t ed25519`; khoá công khai bỏ vào `authorized_keys` trên server) |
| Secret | `DEPLOY_KNOWN_HOSTS` | đầu ra của `ssh-keyscan -t ed25519 <host>` (ghim khoá máy chủ, chống man-in-the-middle) |
| Variable | `DEPLOY_HOST`, `DEPLOY_USER` | địa chỉ và user SSH |
| Variable | `DEPLOY_PATH` | thư mục chứa `.env.prod` trên server, ví dụ `/opt/url-shortener` |
| Variable | `SITE_ADDRESS` | domain, dùng cho bước kiểm tra sau deploy |
| Variable (repo) | `DEPLOY_ENABLED` | `true` để bật bước deploy |

**Rollback:** Actions -> Release -> *Run workflow* -> nhập tag cũ (ví dụ `v1.0.0`). Chỉ deploy lại image của tag đó,
không build lại. Lưu ý rollback **không** hoàn tác migration Flyway đã chạy: nếu bản mới có migration, bản cũ phải
tương thích với schema mới, hoặc phải khôi phục database từ backup (mục 6).

Nên đặt tag từ nhánh `main` đã qua CI. Workflow không kiểm tra tag có nằm trên `main` hay không.

## 6. Sao lưu và khôi phục PostgreSQL

### Cơ chế

Service `backup` trong compose chạy [`backup/backup.sh`](../backup/backup.sh) ở chế độ `loop`:

- mỗi ngày lúc `BACKUP_AT` (mặc định 03:15 **UTC**) chạy `pg_dump` định dạng custom vào `$BACKUP_DIR/daily/`;
- bản dump chỉ được giữ lại nếu `pg_restore --list` đọc được và có `flyway_schema_history`; bản hỏng bị xoá và lần chạy
  tính là thất bại, **không** làm hỏng hay xoá các bản tốt;
- giữ 7 bản hằng ngày; mỗi Chủ nhật sao thêm một bản sang `weekly/`, giữ 4 bản;
- healthcheck của service báo `unhealthy` nếu hơn 26 giờ không có bản thành công (xem bằng `docker compose ps`).

`pg_dump` gồm cả các partition của `url_clicks` và bảng lịch sử Flyway. `url_clicks` lớn dần theo lưu lượng (giữ 13 tháng)
nên kích thước dump cũng tăng theo; hãy theo dõi dung lượng `BACKUP_DIR`.

### Hạn chế quan trọng (đọc kỹ)

- **Backup cùng một VPS không bảo vệ khi mất cả VPS.** Đây là lựa chọn ban đầu (đĩa/thư mục khác trên VPS), chỉ chống
  được lỗi đĩa/xoá nhầm. Bước tiếp theo nên làm sớm: sao chép `BACKUP_DIR` ra **máy khác** (rclone/rsync tới object
  storage hoặc máy thứ hai). Script ghi ra một thư mục thường nên thêm bước đồng bộ không cần sửa script.
- RPO tối đa ~24 giờ: click và link tạo sau lần backup cuối sẽ mất nếu phải khôi phục. Muốn ngắn hơn thì chạy backup nhiều
  lần mỗi ngày hoặc dùng WAL archiving (chưa làm).
- Kafka và Redis **không** được sao lưu. Redis chỉ chứa cache, bộ đếm rate limit và refresh token (mất thì mọi người phải
  đăng nhập lại). Click đang nằm trong Kafka chưa tới DB sẽ mất nếu mất volume Kafka.

### Restore drill (nên làm định kỳ, ví dụ hằng tháng, và ngay sau lần deploy đầu)

Bản backup chưa từng được khôi phục thì chưa phải bản backup.

```bash
./backup/restore-drill.sh "$BACKUP_DIR/daily/$(ls -1 $BACKUP_DIR/daily | sort | tail -1)"
```

Script dựng một container Postgres tạm (không publish cổng, không nối mạng prod), khôi phục bản dump với
`--exit-on-error`, in số dòng các bảng chính và phiên bản Flyway mới nhất để so với hệ thống thật, rồi xoá container. Mã
thoát 0 chỉ khi khôi phục sạch. **Không bao giờ** trỏ nó vào database thật.

### Khôi phục thật sau sự cố

Làm theo thứ tự, và **trước khi thay gì hãy dump bản hiện tại** nếu database còn đọc được.

```bash
cd /opt/url-shortener
set -a; . ./.env.prod; set +a          # nạp DB_NAME, DB_USERNAME, REDIS_PASSWORD, BACKUP_DIR vào shell hiện tại
docker compose --env-file .env.prod -f docker-compose.prod.yml stop app caddy

# 1) khôi phục vào database MỚI cạnh database cũ (không ghi đè)
docker cp "$BACKUP_DIR/daily/dump-....dump" url_shortener_postgres_prod:/tmp/restore.dump
docker exec url_shortener_postgres_prod createdb -U "$DB_USERNAME" "${DB_NAME}_restored"
docker exec url_shortener_postgres_prod pg_restore -U "$DB_USERNAME" -d "${DB_NAME}_restored" --no-owner --exit-on-error /tmp/restore.dump

# 2) đối chiếu (số dòng, phiên bản Flyway) rồi mới đổi tên: ALTER DATABASE cần không còn kết nối nào tới nó
docker exec url_shortener_postgres_prod psql -U "$DB_USERNAME" -d postgres -c \
  "ALTER DATABASE \"$DB_NAME\" RENAME TO \"${DB_NAME}_old\"; ALTER DATABASE \"${DB_NAME}_restored\" RENAME TO \"$DB_NAME\";"

# 3) xoá cache URL trong Redis để không còn trạng thái link cũ, rồi bật lại
docker exec url_shortener_redis_prod sh -c "redis-cli -a '$REDIS_PASSWORD' --no-auth-warning --scan --pattern 'URL:*' | xargs -r redis-cli -a '$REDIS_PASSWORD' --no-auth-warning del"
docker compose --env-file .env.prod -f docker-compose.prod.yml up -d
```

Giữ `${DB_NAME}_old` cho tới khi chắc chắn hệ thống đúng, rồi mới `DROP DATABASE`. Quy trình này **chưa** được diễn tập
trên một server thật (chỉ phần `pg_restore` được kiểm bằng restore drill).

## 7. Điều chỉnh rate limit

Hai lớp, cái nào chạm trước thì trả 429 (kèm `Retry-After`):

1. **Caddy** (thô, theo IP, mỗi phút): `RATE_LIMIT_API_PER_MIN` 240, `RATE_LIMIT_AUTH_PER_MIN` 15,
   `RATE_LIMIT_UNLOCK_PER_MIN` 30, `RATE_LIMIT_ANON_CREATE_PER_MIN` 20, `RATE_LIMIT_REDIRECT_PER_MIN` 600.
   Đổi trong `.env.prod` rồi `docker compose up -d caddy` (không cần build lại).
2. **App** (theo từng nghiệp vụ, trong Redis): xem `app.rate-limit.*` trong `application-prod.yaml`.

Người dùng sau cùng một NAT (văn phòng, mạng di động) dùng chung một IP nên nếu có phàn nàn bị chặn oan, hãy nới các
ngưỡng của Caddy trước. Nếu đặt Cloudflare hoặc load balancer phía trước Caddy: thêm `trusted_proxies` vào Caddyfile và đổi
`key` của rate limit sang `{client_ip}`, đồng thời cấu hình `server.tomcat.remoteip.internal-proxies` của app cho đúng; nếu
không mọi người dùng bị tính chung một IP.

## 8. Giới hạn đã biết

- **Có thời gian ngừng khi deploy.** Một replica app khởi động mất ~60-90 giây; Caddy chỉ thử lại 3 giây, nên trong lúc
  `up -d` thay container app, người dùng gặp lỗi 502/503. Giảm bằng cách chạy ≥ 2 replica (cần bỏ `container_name` của app
  và thử lại quy trình rolling), chưa làm. Khi dừng app, hàng đợi click được xả (tối đa ~45 giây) nên click không mất,
  nhưng consumer Kafka cần cân bằng lại partition (khoảng một phút độ trễ là bình thường).
- Click phát sinh khi Kafka ngừng là mất (không có outbox), xem `docs/load-and-chaos-tests.md`.
- Chưa có giám sát/cảnh báo ngoài log; backup thất bại chỉ hiện ở `docker compose ps` và log của service `backup`.
- Caddy không ghi access log (cố ý, vì app không lưu IP thô). Muốn điều tra tấn công thì bật `log` trong Caddyfile và cân
  nhắc quyền riêng tư.

## 9. Đã kiểm chứng và chưa kiểm chứng

**Đã chạy thật (trên máy phát triển, Docker):**
- Toàn bộ stack prod dưới Caddy: UI, SPA fallback, header bảo mật, API, redirect, actuator bị chặn, giới hạn body.
- Rate limit của Caddy (nhóm auth) và việc app thấy đúng IP client qua `X-Forwarded-For`.
- Luồng link có mật khẩu qua trình duyệt thật (302 sang trang unlock, nhập mật khẩu, chuyển tới đích).
- Dừng app giữa lúc đang chịu tải: không mất click nào.
- Backup: `backup/selftest.sh` (25 kiểm tra trên Postgres thật) gồm xoay vòng, lỗi không làm hỏng bản cũ, restore drill
  thành công và **thất bại đúng** với dump bị cắt cụt; service `backup` trong compose ghi ra thư mục của host.

**Chưa kiểm chứng (cần server thật):**
- HTTPS thật với Let's Encrypt, HTTP/3, bản ghi DNS.
- Toàn bộ workflow GitHub Actions (chưa chạy trên GitHub; chưa kiểm bằng `actionlint`), đẩy/kéo image GHCR, deploy qua SSH,
  rollback.
- Quy trình "Khôi phục thật sau sự cố" ở mục 6 trên dữ liệu thật; kích thước bộ nhớ thực tế của stack trên VPS.
