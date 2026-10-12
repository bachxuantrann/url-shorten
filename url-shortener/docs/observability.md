# Quan sát hệ thống: log, trace, metric qua OTLP (OpenObserve)

Ứng dụng xuất **trace, metric và log** theo OTLP/HTTP tới một backend. Trong dev và (tuỳ chọn) prod dùng **OpenObserve**:
một container duy nhất, khoảng 280 MB RAM, giao diện riêng cho cả ba loại dữ liệu. Vì app chỉ nói OTLP chuẩn nên đổi sang
backend khác (SigNoz, Grafana Cloud, collector riêng...) chỉ là đổi biến môi trường, không đổi code.

Mặc định việc xuất bị **tắt** nếu không có biến (`OTEL_EXPORT_ENABLED=false`); khi tắt, `traceId`/`spanId` vẫn có trong log
console. `.env.dev` đã bật sẵn và trỏ tới OpenObserve của `docker-compose.dev.yml`.

## Biến môi trường

| Biến | Mặc định | Ý nghĩa |
|---|---|---|
| `OTEL_EXPORT_ENABLED` | `false` | `true` để xuất trace + metric + log |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `http://localhost:4318` | Gốc OTLP/**HTTP** của backend; app tự thêm `/v1/traces`, `/v1/metrics`, `/v1/logs`. Với OpenObserve: `http://localhost:5080/api/default` |
| `OTEL_EXPORTER_OTLP_AUTHORIZATION` | rỗng | Giá trị header `Authorization` gửi kèm cả ba loại dữ liệu, ví dụ `Basic <base64(email:password)>` |
| `OTEL_SERVICE_NAME` | `url-shortener` | Tên service |
| `TRACING_SAMPLING_PROBABILITY` | `1.0` dev, `0.1` prod | Tỉ lệ request được ghi trace (metric và log không bị ảnh hưởng) |
| `OTEL_METRICS_STEP` | `30s` | Chu kỳ đẩy metric |

Mỗi instance tự gắn `service.instance.id` = hostname container và `deployment.environment.name` = profile, nên chạy nhiều
instance vẫn phân biệt được. Khi để trống `OTEL_EXPORTER_OTLP_AUTHORIZATION`, app vẫn gửi header `Authorization` rỗng; các
backend không xác thực bỏ qua nó.

## OpenObserve trong môi trường dev

`docker-compose.dev.yml` có service `openobserve` (image ghim `v1.0.4`, ~142 MB):

```bash
docker compose -f docker-compose.dev.yml up -d openobserve      # hoặc `up -d` để bật cả hạ tầng dev
```

- Giao diện: **http://localhost:5080**. Đăng nhập bằng `OPENOBSERVE_EMAIL` / `OPENOBSERVE_PASSWORD` (mặc định dev
  `dev@example.com` / `DevOnly#12345`, chỉ dành cho máy dev; có trong `.env.dev`).
- Tài khoản root chỉ được tạo **ở lần chạy đầu** của volume. Đổi email/mật khẩu sau đó không có tác dụng trừ khi xoá volume
  `url-shortener-dev_openobserve_dev_data`; và `OTEL_EXPORTER_OTLP_AUTHORIZATION` phải tính lại cho khớp:
  `printf 'email:password' | base64`, rồi thêm tiền tố `Basic `.
- Dữ liệu nằm trong volume `openobserve_dev_data`; xoá volume là mất toàn bộ log/trace/metric đã lưu.
- Chưa chạy OpenObserve mà vẫn chạy app: đặt `OTEL_EXPORT_ENABLED=false` trong `.env.dev`, nếu không app báo lỗi xuất dữ liệu.

## OpenObserve cho prod (tuỳ chọn)

`docker-compose.prod.yml` có cùng service dưới profile `observability`, chỉ nghe `127.0.0.1` (truy cập qua SSH tunnel hoặc
reverse proxy có xác thực):

```bash
docker compose -f docker-compose.prod.yml --env-file .env.prod --profile observability up -d
```

Trong `.env.prod` đặt `OTEL_EXPORT_ENABLED=true`, `OTEL_EXPORTER_OTLP_ENDPOINT=http://openobserve:5080/api/default`,
`OPENOBSERVE_EMAIL`/`OPENOBSERVE_PASSWORD` (**giá trị mạnh**, không dùng mặc định dev) và
`OTEL_EXPORTER_OTLP_AUTHORIZATION="Basic <base64(email:password)>"`. Đã có sẵn các dòng `CHANGE_ME_*` trong `.env.prod.example`.
Nếu đã có backend OTLP khác thì không cần bật profile này, chỉ trỏ endpoint (và header xác thực) tới đó.

## Có gì trong trace

Một request `GET /{code}` cho **một trace** gồm: span HTTP server → các span Spring Security → span Redis (`get`/`set`
của cache) → span JDBC (`connection`/`query`/`result-set`, khi cache trượt) → span `url-clicks send` (producer Kafka).
Việc gửi Kafka chạy trên thread khác nhưng vẫn nằm trong cùng trace nhờ truyền ngữ cảnh sang executor.

Consumer xử lý theo **lô** (nhiều click từ nhiều request), nên không thể là con của một trace duy nhất. Mỗi lô là một
trace riêng với span gốc `click.ingest` (kind `CONSUMER`, attribute `messaging.batch.message_count`) có **span link**
tới trace của từng click trong lô (tối đa 50), và các span JDBC của việc ghi nằm dưới nó. Từ giao diện bấm link để lần ngược
về request gốc.

`/actuator/**` (health check) không tạo span.

## Log

Log Logback đi theo OTLP kèm `traceId`/`spanId`, và dòng log console có dạng
`... [thread] [traceId,spanId] LEVEL logger - message`. Từ một trace chậm có thể tìm log của chính trace đó theo `trace_id`.

## Metric tuỳ biến

Tên dưới đây là tên trong code (Micrometer). OpenObserve đổi dấu chấm thành gạch dưới khi lưu, ví dụ
`redirect.latency` thành `redirect_latency` (kèm hậu tố `_bucket`, `_count`, `_sum`, `_max` của histogram).

| Metric | Loại | Tag | Dùng để |
|---|---|---|---|
| `redirect.latency` | timer + histogram | `outcome`: `redirect`, `url_not_found`, `url_gone`, `url_access_blocked`, `url_password_required` | p50/p95/p99 độ trễ redirect |
| `url.cache.requests` | counter | `result`: `hit`, `miss`, `negative_hit`, `corrupt`, `error`, `circuit_open` | tỉ lệ cache hit |
| `click.events` | counter | `result`: `published`, `dropped`, `failed`, `skipped_circuit_open`, `dead_letter` | sức khoẻ đường gửi click |
| `click.ingest.batch.size` | summary | | kích thước lô consumer |
| `click.ingest.inserted` | counter | | click thực sự được ghi (không tính event trùng) |
| `resilience4j.circuitbreaker.state` | gauge | `name`: `redisCache`, `redisRateLimit`, `kafkaPublish`; `state` | trạng thái breaker (1 = đang ở trạng thái đó) |
| `resilience4j.circuitbreaker.calls` | timer | `name`, `kind` | số lần gọi thành công/lỗi/bị chặn |
| `kafka.consumer.fetch.manager.records.lag.max` | gauge | | **độ trễ consumer** (số bản ghi chưa xử lý) |
| `kafka.producer.*`, `kafka.consumer.*` | | | metric client Kafka |
| `http.server.requests`, `jvm.*`, `hikaricp.*`, `lettuce.*`, `process.*` | | | metric có sẵn của Boot |

Gợi ý biểu đồ (viết theo ý, cú pháp truy vấn xem trình dựng của OpenObserve):

- **p99 redirect**: phân vị 0,99 của histogram `redirect_latency_bucket` với `outcome="redirect"`; mục tiêu < 50 ms khi cache trúng.
- **Cache hit ratio**: tốc độ `url_cache_requests{result="hit"}` chia tổng `hit + miss + error + circuit_open`. Tỉ lệ
  `circuit_open` + `error` tăng nghĩa là Redis đang có vấn đề và redirect đang đi thẳng Postgres.
- **Kafka**: `click_events{result=~"failed|dropped|skipped_circuit_open"}` phải gần 0;
  `kafka_consumer_fetch_manager_records_lag_max` tăng liên tục nghĩa là consumer không theo kịp.
- **Circuit breaker**: cảnh báo khi `resilience4j_circuitbreaker_state{state="open"} == 1` kéo dài.

## Trang "Giám sát" trong giao diện (admin)

Mục **Giám sát** ở khu Quản trị (`/admin/monitoring`) cho admin xem log ngay trong ứng dụng mà không phải mở OpenObserve: số dòng
theo mức (bấm để lọc), exception gặp nhiều nhất, tìm trong nội dung log, chọn khoảng 15 phút / 1 giờ / 6 giờ / 24 giờ, tự làm mới
mỗi 10 giây, và nút **Mở OpenObserve** để xem trace và metric. **Backend là bên gọi OpenObserve** (`POST /api/{org}/_search?type=logs`,
xác thực Basic); trình duyệt chỉ gọi `GET /api/v1/admin/monitoring/{status,summary,logs}` (cần `ROLE_ADMIN`) nên thông tin đăng
nhập không bao giờ xuống trình duyệt.

Cấu hình (`app.openobserve.*`, cùng tên biến với phần dựng container): `OPENOBSERVE_URL` (backend gọi: dev `http://localhost:5080`,
trong Docker `http://openobserve:5080`), `OPENOBSERVE_UI_URL` (địa chỉ admin mở được bằng trình duyệt), `OPENOBSERVE_EMAIL`,
`OPENOBSERVE_PASSWORD`. Dev có sẵn giá trị mặc định khớp `docker-compose.dev.yml`. **Prod để trống `OPENOBSERVE_URL` thì tính năng
tắt** (trang báo "Chưa thiết lập giám sát", không gọi gì); khi dùng profile `observability` thì đặt `OPENOBSERVE_URL=http://openobserve:5080`.

An toàn: mức log chỉ nhận ERROR/WARN/INFO/DEBUG/TRACE, từ khoá tối đa 100 ký tự được thoát dấu nháy đơn và dùng
`str_match_ignore_case` (so khớp chuỗi con, `%` và `_` là ký tự thường), khoảng thời gian chỉ nhận 15/60/360/1440 phút, kích thước trang tối
đa 100, chuỗi dài bị cắt (nội dung 4.000 ký tự, stacktrace 8.000). OpenObserve lỗi hoặc không trả lời → 502 `ERR_601` (không lộ chi tiết nội bộ);
chưa cấu hình → 503 `ERR_600`. Chỉ đọc stream log (`app.openobserve.log-stream`, mặc định `default`); trace và metric xem trong chính OpenObserve.

## Đã kiểm chứng và chưa kiểm chứng

Đã kiểm chứng với **OpenObserve thật** (`v1.0.4`, hai instance app qua nginx, 2.000 request k6, truy vấn lại bằng API của nó):
24.720 span của service `url-shortener` (`http get /{code}`, Security, Redis `get`/`set`, JDBC `query`, `url-clicks send`,
`click.ingest`), log, và các stream metric `redirect_latency*`, `url_cache_requests`, `click_events`, `click_ingest_*`,
`resilience4j_circuitbreaker_state`, `kafka_consumer_fetch_manager_records_lag*`; không có lỗi xuất dữ liệu ở app.

Đã kiểm chứng bằng test tự động (`ObservabilityIntegrationTest`, Postgres/Redis/Kafka thật): trace HTTP + Redis + JDBC + Kafka
trong một trace, span link của consumer, các metric tuỳ biến, metric breaker và lag consumer, việc **trace, metric, log
thực sự được gửi** tới một collector OTLP/HTTP giả với đúng `service.name`, và header `Authorization` có mặt trên cả ba loại dữ liệu.

Trang Giám sát đã chạy với OpenObserve thật `v1.0.4` ở dev (đếm theo mức, tìm kiếm, chi tiết, 375 px) và có test tự động với một OpenObserve giả
(`AdminMonitoringIntegrationTest`, `MonitoringServiceImplTest`, `monitoring.test.tsx`).

**Chưa** kiểm chứng: cách hiển thị trên giao diện OpenObserve, cú pháp truy vấn dashboard, cảnh báo, và việc chạy
profile `observability` của `docker-compose.prod.yml` trên một máy prod thật.
