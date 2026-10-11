# Thử tải (k6), nhiều instance (nginx) và thử sự cố (chaos)

Mọi thứ chạy bằng Docker, tách biệt hoàn toàn với stack dev (project `url-shortener-scale`, mạng và volume riêng,
chỉ mở cổng 8081 của nginx ra máy host).

## Dựng stack

```bash
cd url-shortener
docker compose -f docker-compose.scale.yml up -d --build   # 2 instance app + nginx + postgres/redis/kafka
docker compose -f docker-compose.scale.yml restart nginx    # chỉ cần khi đổi số instance (--scale app=N)
docker compose -f docker-compose.scale.yml down -v          # xoá sạch, kể cả dữ liệu thử
```

- nginx cân bằng tải round-robin giữa các instance, loại instance chết khỏi vòng quay 5 giây, và thử lại request GET
  lỗi kết nối trên instance khác (`load-test/nginx.conf`).
- App chạy profile `prod` với các giá trị thử nghiệm cố định trong `docker-compose.scale.yml` (không phải bí mật thật).
  Topic click có 4 partition để cả hai instance cùng consume.

## Thử tải redirect (k6)

```bash
docker run --rm -i -e BASE_URL=http://host.docker.internal:8081 -e RATE=300 -e DURATION=30s \
  grafana/k6 run - < load-test/k6/redirect.js
```

Biến: `RATE` (req/s), `DURATION`, `CODE` (dùng link có sẵn, bỏ qua đăng nhập), `ADMIN_USER`/`ADMIN_PASS`. Ngưỡng thất bại
trong script: **p99 < 50 ms**, p95 < 25 ms, lỗi < 1%. Mỗi lần chạy tạo một link rồi redirect link đó nên gần như toàn bộ
là cache hit. **Chạy một lượt khởi động (warm-up) trước khi đo**: JVM mới khởi động có p99 cao hơn nhiều (xem bảng).

Lấy mã link có sẵn để dùng `CODE`:
`docker exec url-shortener-scale-postgres-1 psql -U scale -d scale_db -t -A -c "select short_code from urls order by id desc limit 1"`

## Kết quả đã đo

Máy dev Windows + Docker Desktop, k6 chạy trên **cùng máy** (tranh CPU với app), 2 instance app, qua nginx. Số liệu
mang tính tham khảo, không phải benchmark của môi trường production.

| Kịch bản | Request | Lỗi | p95 | p99 | Ghi chú |
|---|---|---|---|---|---|
| 300 req/s, **JVM vừa khởi động** | 9.003 | 0 | 13,7 ms | **108 ms (không đạt 50 ms)** | JIT chưa nóng |
| 300 req/s, đã warm | 12.003 | 0 | 3,8 ms | 14 ms | đạt |
| 1.000 req/s, đã warm | 40.003 | 0 | 4,0 ms | 16 ms | đạt, thực đo 1.105 req/s |

### Sự cố (chaos), mỗi kịch bản 300 req/s trong lúc gây sự cố

| Sự cố | Lỗi | p95 | p99 | Hành vi quan sát được |
|---|---|---|---|---|
| **Redis chết** | 0 / 11.988 | 9,9 ms | 77 ms (max 0,47 s) | Redirect đi thẳng Postgres. Chỉ ~60 cảnh báo mỗi instance trước khi breaker mở (không phải hàng nghìn). Redis sống lại thì breaker tự đóng, p99 về bình thường. Click vẫn được ghi **đầy đủ** (11.965/11.965 ở lần đo đầu). |
| **Kafka chết** | 0 / 12.001 | 2,8 ms | 6 ms | Redirect không bị ảnh hưởng. **Click trong lúc broker chết bị mất** (12.001/12.001). Sau khi Kafka sống lại, pipeline tự hồi phục: 8.998/9.001 click của lượt kế tiếp được ghi. |
| **Tắt một instance** giữa tải | 0 / 13.501 | 3,3 ms | 13 ms (max 1,0 s) | nginx thử lại trên instance còn lại (1,0 s là `proxy_connect_timeout` của vài request đang bay). Số click tăng **đúng +13.501**: không mất sự kiện, consumer còn lại nhận phần việc của instance chết. |

Sau toàn bộ các sự cố: `urls.click_count` = số dòng `url_clicks` = tổng `url_daily_stats.clicks` = 116.469, không link nào lệch.

Khi Redis chết, p99 phụ thuộc `REDIS_TIMEOUT`: với timeout 500 ms đo được p99 505 ms (max 1,03 s), với 300 ms (mặc định hiện
tại) là 77 ms. Đó là thời gian các request đầu tiên chờ Redis trước khi circuit breaker mở; sau đó p95 ~5–10 ms.

### Làm lại từng sự cố

```bash
CODE=<mã link>
RUN='docker run --rm -i -e BASE_URL=http://host.docker.internal:8081 -e RATE=300 -e DURATION=40s -e CODE=$CODE grafana/k6 run -'

docker stop  url-shortener-scale-redis-1;  $RUN < load-test/k6/redirect.js; docker start url-shortener-scale-redis-1   # Redis chết
docker stop  url-shortener-scale-kafka-1;  $RUN < load-test/k6/redirect.js; docker start url-shortener-scale-kafka-1   # Kafka chết
# Tắt một instance giữa lúc đang tải: chạy k6 ở nền (DURATION=45s), sau ~15 giây:
docker stop url-shortener-scale-app-2; ...; docker start url-shortener-scale-app-2
```

Đối chiếu số click: `select count(*) from url_clicks` (qua `docker exec url-shortener-scale-postgres-1 psql -U scale -d scale_db`).

## Điều cần biết (giới hạn đã biết)

- **Kafka chết = mất click trong thời gian đó** (ở mức "tối đa một lần" phía producer). Đây là đánh đổi có chủ ý để redirect
  không bao giờ bị chặn. Muốn không mất cần một bộ đệm bền phía app (outbox); chưa làm.
- **Breaker Kafka mở trễ vài giây.** Khi broker chết mà producer còn metadata cũ, `send()` không chặn mà bản ghi nằm trong bộ
  đệm tới khi hết `delivery.timeout.ms` (5 giây) mới báo lỗi, nên breaker chỉ mở sau ~5 giây (khoảng 750 bản ghi mỗi instance ở
  150 req/s). Hạ `delivery.timeout.ms` sẽ mở nhanh hơn nhưng dễ báo lỗi giả khi Kafka chỉ chậm tạm thời (đổi leader).
- **nginx phân giải DNS một lần lúc khởi động.** Đổi số instance hoặc instance đổi IP phải restart nginx.
- Số liệu ở trên đo trên một máy duy nhất, p99 phụ thuộc mạnh vào tải của máy lúc đó và độ nóng của JVM; đừng dùng làm cam kết
  SLA. Cần đo lại trên hạ tầng thật trước khi tin vào mục tiêu p99 < 50 ms.
- Chưa thử: Postgres chết, mạng chập chờn (độ trễ/mất gói), tải kéo dài hàng giờ, hoặc nhiều hơn 2 instance.
