# CÔNG NGHỆ SỬ DỤNG VÀ LOGIC XỬ LÝ KIẾN TRÚC HỆ THỐNG

Tài liệu này giải thích **dùng công nghệ gì, vì sao chọn, và hệ thống xử lý từng việc như thế nào** ở mức kiến trúc. Chức năng nhìn từ phía người dùng xem `thiet-ke-chi-tiet.md`; cách triển khai xem `deployment.md`.

---

## 1. Tech stack

### 1.1. Backend
| Thành phần | Công nghệ (phiên bản) | Vai trò |
|---|---|---|
| Ngôn ngữ / runtime | Java 21 (Eclipse Temurin) | Ngôn ngữ chính; image chạy dùng JRE Alpine nhỏ gọn |
| Framework | Spring Boot 4.1.1 (Web MVC, Data JPA, Security, Validation, Actuator) | REST API theo lớp controller → service → repository |
| CSDL | PostgreSQL 16 | Nguồn dữ liệu duy nhất (người dùng, link, click, thống kê, báo cáo, kháng nghị) |
| Quản lý lược đồ | Flyway (V1…V11) | Mọi thay đổi bảng đi qua migration; Hibernate chỉ `validate` |
| Cache / bộ đếm | Redis 7 (Lettuce) | Cache chuyển hướng, giới hạn tốc độ, token (refresh, danh sách đen), cờ khoá tài khoản |
| Hàng đợi sự kiện | Apache Kafka (Confluent cp-kafka 7.6) | Ghi click bất đồng bộ, có thử lại và topic lỗi |
| Xác thực | Spring Security + JWT (jjwt 0.12.6), BCrypt | Không phiên, không `UserDetailsService`; claim mang `userId`, `username`, `roles`, `sid` |
| Ánh xạ | MapStruct 1.6.3 + Lombok | Entity ↔ DTO, bỏ mã lặp |
| Chịu lỗi | Resilience4j 2.4.0 (core, lập trình) | Cầu dao ngắt mạch cho Redis và Kafka |
| Mã QR | ZXing 3.5.3 | Sinh PNG tại chỗ, không lưu |
| Phân tích click | uap-java 1.6.1 (trình duyệt, hệ điều hành), GeoIP2 4.2.1 (quốc gia, file MaxMind) | Làm giàu dữ liệu click ở phía consumer |
| Quan sát | OpenTelemetry (OTLP) + Micrometer + logback appender, backend OpenObserve v1.0.4 | Log, trace, metric trong một nơi |
| Kiểm thử | JUnit 5, Spring Boot Test, Testcontainers (PostgreSQL, Redis, Kafka), Awaitility | 538 test; test tích hợp chạy trên container thật |

### 1.2. Frontend
| Thành phần | Công nghệ | Vai trò |
|---|---|---|
| UI | React 19, TypeScript 7 | Giao diện SPA |
| Build / dev | Vite 8 | Dev server cổng 5173, proxy `/api` sang backend để cùng origin |
| Định tuyến | react-router-dom 7 | Các trang công khai, người dùng, quản trị |
| Biểu đồ | Recharts 3 (tải theo nhu cầu) | Biểu đồ click theo ngày; tách khỏi bundle chính |
| Biểu tượng, font | lucide-react, Inter (đóng gói sẵn) | Font phục vụ từ chính origin nên CSP `default-src 'self'` vẫn đúng |
| Kiểm thử | Vitest 5, Testing Library | 333 test với `fetch` giả |
| Giao diện | CSS thuần với biến thiết kế, sáng/tối, Việt/Anh | Không phụ thuộc thư viện UI |

### 1.3. Hạ tầng và vận hành
| Thành phần | Công nghệ | Vai trò |
|---|---|---|
| Reverse proxy | Caddy 2.11.7 (build bằng xcaddy + plugin giới hạn tốc độ) | Cổng duy nhất mở 80/443: HTTPS tự động, giới hạn tốc độ theo IP, phục vụ giao diện tĩnh |
| Đóng gói | Docker, Docker Compose (dev, prod, scale) | Dev chạy hạ tầng trong compose, ứng dụng chạy từ IDE; prod chạy cả ứng dụng |
| Sao lưu | Dịch vụ `backup` (`pg_dump -Fc` hằng ngày) | Xoay vòng 7 ngày + 4 tuần, mỗi bản được kiểm tra, có kịch bản phục hồi thử |
| CI/CD | GitHub Actions (`ci.yml`, `release.yml`) | Test, build 2 image lên GHCR, deploy bằng SSH khi bật |
| Kiểm tra tải | k6 (qua Docker), nginx 2 bản ứng dụng | Đo p99, thử sự cố Redis/Kafka/ứng dụng |

### 1.4. Vì sao chọn như vậy
- **PostgreSQL làm nguồn sự thật duy nhất:** mọi thứ cần đúng (link, số click, thống kê) nằm ở một nơi có giao dịch; Redis và Kafka chỉ là lớp tăng tốc/đệm, mất đi không làm mất dữ liệu gốc.
- **Redis cho đường nóng:** chuyển hướng là thao tác nhiều nhất và cần nhanh nhất; tra khoá đơn trong Redis rẻ hơn nhiều so với truy vấn CSDL.
- **Kafka cho click:** ghi một dòng cho mỗi click vào CSDL ngay trong request sẽ làm chậm chuyển hướng và dồn tải khi có đỉnh; hàng đợi tách việc ghi ra khỏi đường phản hồi và cho phép gộp lô.
- **JWT không phiên:** dễ chạy nhiều bản ứng dụng sau proxy mà không cần phiên dùng chung.
- **Caddy:** HTTPS tự động và giới hạn tốc độ trước khi request chạm ứng dụng; ứng dụng không mở cổng ra ngoài.
- **Một nhà cung cấp quan sát chuẩn OTLP:** đổi backend giám sát chỉ cần đổi biến môi trường.

---

## 2. Kiến trúc tổng thể

```
                       ┌───────────────────────────────────────────────┐
  Trình duyệt ───────► │ Caddy :80/:443                                │
  (SPA React)          │  • HTTPS tự động, HTTP/3                      │
                       │  • giới hạn tốc độ theo IP (api, auth, mở     │
                       │    khoá, tạo ẩn danh, chuyển hướng)           │
                       │  • phục vụ file tĩnh giao diện + SPA fallback │
                       │  • /actuator/* → 404                          │
                       └───────┬───────────────────────────────────────┘
                               │ /api/*  và  /{mã}
                               ▼
                       ┌───────────────────────────────┐
                       │ Ứng dụng Spring Boot (không   │
                       │ mở cổng ra ngoài)             │
                       └──┬─────────┬──────────┬───────┘
              nguồn sự thật│   cache, đếm,│  sự kiện click│
                          ▼  token, khoá ▼               ▼
                   PostgreSQL            Redis           Kafka ──► consumer trong chính ứng dụng
                   (+ phân vùng click)                      url-clicks (+ .DLT)        │
                          ▲                                                           │
                          └───────────────────── ghi click + thống kê ◄───────────────┘
                   Log / trace / metric ──OTLP──► OpenObserve
```

**Nguyên tắc thiết kế xuyên suốt**
1. **Đường chuyển hướng không bao giờ phụ thuộc vào thứ không thiết yếu.** Redis hỏng → dùng PostgreSQL. Kafka hỏng → vẫn chuyển hướng (chỉ mất click trong lúc đó).
2. **Một câu lệnh SQL cho một thao tác hàng loạt.** Số câu lệnh không tăng theo số dòng (không N+1); điều này được kiểm chứng bằng test đếm câu lệnh.
3. **Ghi idempotent.** Kafka giao tối thiểu một lần nên consumer ghi bằng `ON CONFLICT DO NOTHING`, nhận lại sự kiện không đếm hai lần.
4. **Mọi thay đổi trạng thái link xoá cache sau khi commit.**
5. **Riêng tư theo thiết kế.** Không lưu IP thô, chỉ lưu mã băm có khoá bí mật.

---

## 3. Kiến trúc backend theo lớp

```
controller   ── nhận request, kiểm tra đầu vào (@Valid), gọi giới hạn tốc độ, trả ApiResponse
   │
service (interface) / service/impl  ── toàn bộ nghiệp vụ, quản lý transaction
   │
repository (Spring Data JPA + câu lệnh native/JPQL cho thao tác hàng loạt)
   │
PostgreSQL

Chéo các lớp: security (JWT filter) · exception (AppException + ErrorCode) · utils (cache, giới hạn tốc độ,
              sinh mã, chính sách URL đích) · config (thuộc tính, bean) · job (tác vụ định kỳ)
```

- **Xử lý lỗi tập trung:** mọi lỗi nghiệp vụ ném `AppException(ErrorCode)`; `GlobalExceptionHandler` và các handler 401/403 của Security cùng dựng một khuôn `ApiResponse`, thông điệp dịch theo `Accept-Language` (tiếng Việt/Anh).
- **Kiểm toán:** entity kế thừa `BaseEntity` (`createdAt/updatedAt` do Hibernate; `createdBy/updatedBy` do Spring Data auditing lấy từ người đăng nhập, `anonymous` hoặc `system`). Câu lệnh hàng loạt bỏ qua auditing nên tự đặt các cột này.
- **Thời gian:** luôn dùng bean `Clock` (không gọi `now()` trực tiếp) để test cố định được thời gian.

---

## 4. Logic xử lý các luồng chính

### 4.1. Xác thực và phiên

**Đăng nhập:** kiểm tra mật khẩu BCrypt và trạng thái tài khoản → tạo `sessionId` ngẫu nhiên → phát access token (15 phút) và refresh token (7 ngày), cả hai mang claim `sid` → lưu refresh token vào Redis `RT:{userId}:{sessionId}`.

**Mỗi request có token:** `JwtAuthenticationFilter` chỉ đọc claim (userId, username, roles) và hỏi Redis hai điều: token có trong danh sách đen `BL:{jti}` không, và tài khoản có bị đánh dấu khoá `LOCK:{userId}` không. **Không chạm CSDL.** Token sai thì request đơn giản không được xác thực (Security trả 401); tài khoản bị khoá thì để lại thuộc tính để điểm vào trả 403 `ERR_104` (không phải 401, vì 401 sẽ khiến giao diện cố làm mới token).

**Làm mới token:** so khớp token nhận được với bản trong Redis; kiểm tra trạng thái người dùng từ CSDL.

**Đăng xuất:** đưa `jti` của access token vào danh sách đen (TTL = thời gian còn lại) và xoá khoá refresh của phiên đó. Không có "đăng xuất mọi thiết bị" (đã quyết định không cần).

**Hệ quả có chủ đích:** vì filter không hỏi CSDL nên **đổi vai trò chỉ có hiệu lực khi access token hết hạn**; còn **khoá tài khoản có hiệu lực ngay** nhờ cờ `LOCK:` (TTL = hạn access token + 1 phút). Refresh token cố ý không bị xoá khi khoá để người quay lại vẫn thấy lý do khi đăng nhập/làm mới.

### 4.2. Sinh mã rút gọn
1. Lấy giá trị kế tiếp của dãy PostgreSQL `short_code_seq`.
2. Hoán vị bằng mạng Feistel có khoá bí mật (có "cycle walking" để vẫn nằm trong miền 62⁷).
3. Đổi sang Base62, 7 ký tự.

Vì hoán vị là song ánh nên **mã không bao giờ trùng** mà không cần kiểm tra, và không thể đoán hay đếm số link. Khoá `SHORT_CODE_SECRET` phải **không bao giờ đổi** sau khi đã phát hành mã.

**Xung đột alias với mã sinh ra:** một alias 7 ký tự có thể trùng mã sinh ra sau này. Câu `INSERT` vi phạm ràng buộc duy nhất làm hỏng cả transaction của PostgreSQL, nên mỗi lần thử tạo link chạy trong **transaction riêng** (`TransactionTemplate`) và vòng lặp bên ngoài thử lại với mã mới (tối đa 5 lần). Với alias thì không thử lại mà trả 409. Việc băm BCrypt cũng làm ngoài transaction để không giữ kết nối CSDL.

### 4.3. Chuyển hướng (đường nóng)

```
GET /{mã}
  │ SecurityConfig: mã một đoạn, ký tự hợp lệ, không thuộc từ khoá dành riêng → cho qua không cần token
  ▼
RedirectService
  1. Kiểm tra độ dài mã (3–32)
  2. Tra Redis  URL:{mã}  = TRẠNG_THÁI|hạn|có_mật_khẩu|URL_đích   hoặc  MISSING
        trúng → dùng
        trượt hoặc Redis lỗi → tra PostgreSQL, nạp lại cache
  3. Quyết định:
        không có / bị tắt / mã dành riêng → 404
        hết hạn (EXPIRED, hoặc ACTIVE đã quá hạn) / đã xoá → 410
        BLOCKED → 403
        có mật khẩu → 403 ERR_408  (trình duyệt: 302 tới /unlock/{mã})
        còn lại → 302 + Location + Cache-Control: no-store
  4. Nếu 302: đẩy sự kiện click (không chặn)
```

- **Cache âm** (`MISSING`, sống ngắn 1 phút) chống việc dò mã bắt CSDL gánh. TTL cache bình thường tối đa 10 phút và không vượt quá thời gian còn lại tới hạn dùng.
- **Cache không chứa URL đích của link có mật khẩu.**
- **Tại sao 302, không phải 301:** 301 bị trình duyệt cache vĩnh viễn nên mất lượt click và không đổi đích được.
- **Cạnh tranh nhỏ có chủ đích:** một người đọc có thể nạp CSDL ngay trước lúc commit rồi ghi cache ngay sau lúc xoá cache, để lại bản cũ tối đa một chu kỳ `cache-ttl`.
- **Cầu dao `redisCache`** bao việc đọc/ghi cache; thao tác **xoá cache cố ý không qua cầu dao** (phải thử thật, nếu không bản cũ còn sót khi Redis hồi phục). Thời gian chờ lệnh Redis là 300 ms để Redis chết không làm treo mọi request.

### 4.4. Ghi click (Kafka)

**Phía producer (trên luồng request, không chặn):**
1. Đọc IP, User-Agent, quốc gia (tra GeoIP trong bộ nhớ) ngay trên luồng request.
2. Giao việc gửi cho `clickPublishExecutor` (hàng đợi giới hạn, chính sách `AbortPolicy`): hàng đợi đầy thì **bỏ sự kiện** và đếm số bị bỏ; gửi lỗi thì đếm lỗi; không bao giờ ném lỗi ra request.
3. Cấu hình producer ngắn: chờ khối tối đa 500 ms, hết thời gian giao 5 giây. Cầu dao `kafkaPublish` bỏ qua sự kiện khi đang mở.

**Sự kiện** (`ClickEvent`, dạng JSON): `eventId` (UUID duy nhất), mã link, thời điểm, mã băm khách, User-Agent, quốc gia, `traceparent`. Khoá phân vùng = mã link (các click cùng link vào cùng phân vùng).

**Phía consumer (nhận theo lô):**
- Thông điệp hỏng (JSON sai, thiếu trường, `eventId` không phải UUID) → chuyển thẳng sang `url-clicks.DLT` rồi bỏ qua.
- Phân tích User-Agent thành trình duyệt / hệ điều hành / loại thiết bị (MOBILE, TABLET, DESKTOP, BOT; bot vẫn được lưu, đếm vào `click_count` nhưng không tính khách duy nhất).
- Đổi mã link sang id bằng **một** truy vấn `IN (...)`; mã không biết thì ghi log và bỏ qua.
- Ghi **một câu SQL duy nhất** cho cả lô (các mảng được `unnest`):
  1. `INSERT INTO url_clicks ... ON CONFLICT DO NOTHING RETURNING` — chỉ các dòng thực sự chèn mới được tính tiếp;
  2. cộng dồn `url_daily_stats` (clicks, bot_clicks, unique_visitors) theo (link, ngày);
  3. chèn khách mới vào `url_daily_visitors` (`ON CONFLICT DO NOTHING`) — khách là "mới" khi dòng chèn thành công, đúng cả khi nhiều bản ứng dụng chạy song song;
  4. `UPDATE urls SET click_count = click_count + n`.
  Các delta đều tính từ những dòng thực sự chèn nên **nhận lại sự kiện không bao giờ đếm đôi** và các bộ đếm không lệch nhau. Các CTE chèn theo thứ tự `ORDER BY` để giảm khoá chéo giữa các lô.
- Lỗi hạ tầng (CSDL tắt) → `DefaultErrorHandler` thử lại có lùi dần (tối đa 15 giây), hết lượt thì cả lô sang `.DLT`.
- **Hậu quả được chấp nhận và đã ghi nhận:** click gửi lúc Kafka tắt bị mất (chưa có outbox); cầu dao Kafka mở chậm khoảng 5 giây do `delivery.timeout.ms`.

### 4.5. Thống kê
- Một link: **3 câu lệnh cố định** — tra link; đọc `url_daily_stats` theo khoảng ngày (điền 0 cho ngày thiếu); một lần quét `url_clicks` bằng `GROUPING SETS` cho cả bốn bảng phân tích (quốc gia, thiết bị, trình duyệt, hệ điều hành). Bảng phân tích chỉ có trong thời hạn giữ dữ liệu thô; chuỗi theo ngày thì vĩnh viễn.
- Tổng quan tài khoản: **4 câu lệnh** bất kể số link (qua `JdbcTemplate`); click của link đã xoá bị loại.
- Khoảng ngày tối đa 366 ngày; ngày tính theo múi giờ máy chủ (`stat_date` là ngày địa phương của máy chủ).
- **Khách duy nhất chính xác theo ngày**, không ước lượng. Đánh đổi: bảng `url_daily_visitors` có một dòng cho mỗi khách mỗi link mỗi ngày, bị xoá theo thời hạn giữ dữ liệu; nếu quá lớn thì thay bằng HyperLogLog trong Redis hoặc phân vùng theo tháng.

### 4.6. Công việc nền
| Công việc | Chu kỳ | Việc làm | An toàn nhiều bản |
|---|---|---|---|
| `UrlExpirationJob` | 1 phút | Chuyển link `ACTIVE` quá hạn sang `EXPIRED`, một câu `WITH due AS (... FOR UPDATE SKIP LOCKED) UPDATE ... RETURNING` cho mỗi lô 500, rồi xoá cache bằng một lệnh `DEL` nhiều khoá | `SKIP LOCKED`: mỗi bản chiếm các dòng khác nhau |
| `ClickPartitionJob` | 6 giờ | Tạo trước phân vùng các tháng tới, xoá phân vùng và `url_daily_visitors` cũ hơn 13 tháng | `pg_try_advisory_xact_lock` |
| `ClickReconciliationJob` | 24 giờ | Đối soát `urls.click_count` với tổng `url_daily_stats`, và `url_daily_stats` với `url_clicks` 2 ngày gần nhất; mặc định chỉ ghi cảnh báo, bật `reconcile-repair` thì sửa dưới khoá dòng | Khoá dòng `FOR UPDATE` rồi tính lại |

Hết hạn **luôn được kiểm tra lúc chuyển hướng**; job chỉ đồng bộ trạng thái nên đúng đắn không phụ thuộc job có chạy hay không.

### 4.7. Chống lạm dụng

**Giới hạn tốc độ (hai tầng):**
- **Caddy** theo IP mỗi phút: API 240, đăng nhập 15, mở khoá 30, tạo ẩn danh 20, chuyển hướng 600. Phản hồi 429 có `Retry-After`, thân rỗng.
- **Ứng dụng (Redis, cửa sổ cố định)** theo nghiệp vụ: một script Lua nguyên tử `INCR` + `EXPIRE` trên khoá `RL:{nhóm}:{chủ thể}`. Gồm tạo ẩn danh, tạo link, các lớp mở khoá, báo cáo, kháng nghị. Mọi request đều tính (kể cả request bị từ chối vì URL xấu). **Khi Redis hỏng thì mở cửa (fail-open)** qua cầu dao `redisRateLimit`. Kiểm tra định dạng mã **trước** khi tạo khoá để mã bịa không sinh vô hạn khoá Redis.

**IP khách:** lấy từ `request.getRemoteAddr()`; `server.forward-headers-strategy: native` chỉ cho Tomcat thay bằng `X-Forwarded-For` khi kết nối đến từ dải mạng riêng (chính là Caddy trong cùng mạng Docker), nên khách từ Internet không giả được. Không bao giờ đọc header đó thủ công.

**Chính sách URL đích** (`UrlTargetPolicy`, chạy khi tạo và khi sửa): chặn chính hệ thống, địa chỉ nội bộ/dự trữ ở mọi cách viết, tên nội bộ, rồi tra danh sách domain bị chặn (host và mọi domain cha, một truy vấn). **Việc kiểm tra thuần cú pháp, không phân giải DNS**: nếu sau này có tính năng máy chủ tự tải URL đích (xem trước, quét mã độc) thì bắt buộc kiểm tra lại IP đã phân giải.

**Kiểm duyệt:** admin chặn link (lý do bắt buộc) hoặc chặn cả domain (có thể chặn luôn các link hiện có bằng một câu `UPDATE ... RETURNING` theo regex host). Mọi thay đổi xoá cache sau commit.

### 4.8. Báo cáo lạm dụng và kháng nghị
- Cả hai dùng cùng một kỹ thuật ghi chống trùng: **`INSERT ... ON CONFLICT DO NOTHING`** trả 0/1, nên hai request đồng thời không làm hỏng transaction mà chỉ một cái thành công. Báo cáo: duy nhất `(link, mã băm người báo)`. Kháng nghị: chỉ mục duy nhất một phần `(link) WHERE status='OPEN'`.
- **Danh sách của admin** gom theo link bằng một truy vấn có `GROUP BY` kèm một truy vấn đếm lý do cho cả trang (`IN`), nên số câu lệnh cố định.
- **Đóng báo cáo / kháng nghị** bằng câu `UPDATE ... WHERE status='OPEN'` có điều kiện; hai admin thao tác cùng lúc thì một người thắng.
- **Chấp nhận kháng nghị** gỡ chặn link, xoá cache và đóng kháng nghị trong **một transaction**.
- **Hiển thị trạng thái kháng nghị cho chủ link** dùng một truy vấn `IN (...)` cho cả trang link; chỉ kháng nghị còn liên quan đến lần chặn hiện tại mới hiện (lời từ chối cũ biến mất khi link bị chặn lại).

### 4.9. Quyền riêng tư
- **Không lưu IP thô** ở Kafka hay CSDL.
- Khách duy nhất: `HMAC-SHA256(khoá, ngày | IP | User-Agent)` cắt còn 32 ký tự hex — có ngày trong đầu vào nên "duy nhất" chỉ trong một ngày. Khoá `CLICK_VISITOR_SECRET` đổi được (chỉ làm lệch số khách duy nhất một lần).
- Người báo cáo: mã băm **không có ngày** trong đầu vào để nhận ra cùng một người qua các ngày, vẫn không lưu IP.
- Quốc gia chỉ lấy từ file GeoIP cục bộ; header do client gửi (ví dụ `CF-IPCountry`) bị bỏ qua.

---

## 5. Kiến trúc dữ liệu

- **Phân vùng theo tháng** cho `url_clicks` (`url_clicks_yYYYYmMM`) cùng phân vùng mặc định để click ngoài khoảng không bao giờ mất; xoá dữ liệu cũ bằng cách bỏ cả phân vùng (rẻ hơn nhiều so với `DELETE`).
- **Hai tầng thống kê:** bảng thô `url_clicks` (giữ 13 tháng, dùng cho các bảng phân tích) và bảng tổng hợp `url_daily_stats` (vĩnh viễn, dùng cho biểu đồ và tổng).
- **Xoá mềm** link (`status = DELETED`, `deleted_at`); mã không bao giờ được cấp lại.
- **`User.roles`** là tập `EAGER` nên đã gắn `@BatchSize(100)`: danh sách nhiều user nạp quyền bằng một câu `IN` thay vì mỗi user một câu; không thay bằng `JOIN FETCH` trên truy vấn phân trang (Hibernate sẽ phân trang trong bộ nhớ).
- **Quy tắc chống N+1** áp cho mọi truy vấn mới: số câu lệnh không được tăng theo số dòng, và được chứng minh bằng test đếm câu lệnh (thống kê Hibernate, hoặc timer `jdbc.query` của Micrometer với `JdbcTemplate`).

---

## 6. Chịu lỗi và độ tin cậy

| Sự cố | Hành vi | Ghi chú |
|---|---|---|
| Redis chậm / hỏng | Chuyển hướng dùng PostgreSQL; giới hạn tốc độ mở cửa | Cầu dao `redisCache`, `redisRateLimit`; thời gian chờ 300 ms |
| Kafka hỏng | Chuyển hướng bình thường, click trong lúc đó mất | Cầu dao `kafkaPublish` mở sau khoảng 5 giây; chưa có outbox |
| Hàng đợi click đầy | Bỏ sự kiện, không chậm request | Có bộ đếm `droppedCount` |
| CSDL tắt khi consumer ghi | Thử lại có lùi dần, hết lượt thì cả lô sang DLT | Chưa có nơi đọc DLT |
| Một bản ứng dụng chết | Bản còn lại tiếp tục (đã thử với 2 bản sau nginx) | Prod hiện chạy một bản |
| Tắt máy chủ | Tắt êm 30 giây, xả nốt hàng đợi click rồi thoát | `server.shutdown: graceful` |
| Mất VPS | Mất cả dữ liệu lẫn sao lưu cùng máy | Chưa chép sao lưu ra ngoài |

Kết quả đo đã ghi trong `load-and-chaos-tests.md`: p99 chuyển hướng 14–16 ms ở 300–1000 req/s (đã làm nóng JVM; khởi động nguội ra 108 ms), 0 lỗi khi tắt Redis, Kafka hoặc một bản ứng dụng.

---

## 7. Quan sát (observability)

- **Xuất qua OTLP/HTTP** (log, trace, metric) sang OpenObserve; bật bằng `OTEL_EXPORT_ENABLED=true`; địa chỉ và tiêu đề `Authorization` lấy từ biến môi trường. Mã trace luôn có trong dòng log dù có xuất hay không.
- **Một trace cho một lượt chuyển hướng:** HTTP → Spring Security → Redis → JDBC → Kafka producer (luồng gửi được bọc `ContextExecutorService` để giữ ngữ cảnh). Consumer nhận theo lô nên Spring Kafka không tự theo dõi; `ClickEventConsumer` tạo span `click.ingest` cho mỗi lô và **liên kết (span link)** tới `traceparent` của từng click (tối đa 50).
- **Số liệu tuỳ biến** tập trung ở `AppMetrics`: độ trễ chuyển hướng theo kết quả, tỷ lệ trúng cache, số sự kiện click theo kết quả, số liệu ghi lô; trạng thái cầu dao qua `resilience4j-micrometer`; độ trễ consumer Kafka do Boot gắn sẵn.
- Không ghi nhật ký truy cập ở Caddy (ứng dụng cố ý không giữ IP thô).

---

## 8. Kiến trúc frontend

- **SPA cùng origin:** UI và API ở cùng một tên miền (dev nhờ proxy Vite, prod nhờ Caddy) nên không cần CORS.
- **Một lớp HTTP** (`src/api/client.ts`): gắn `Accept-Language`, gắn Bearer, làm mới token một lần khi 401 (single-flight), ném `ApiError` — mã giao diện **chỉ rẽ nhánh theo `code`**, không theo `message`. Riêng ảnh QR cần token nên tải bằng `requestBlob`.
- **Bố cục:** `PublicLayout` (trang chủ, mở khoá, trang link chết), `AuthLayout` (đăng nhập/đăng ký), `AppLayout` (khung có thanh bên, hộp thoại tạo link dùng chung); khu quản trị bọc `RequireAdmin` (chỉ ẩn giao diện, quyền thật do server kiểm tra).
- **Thời gian:** API dùng `LocalDateTime` theo múi giờ máy chủ, nên `useServerTime` tải múi giờ từ `GET /public/config` một lần (lười) và quy đổi hiển thị/nhập; các trang chờ có múi giờ rồi mới vẽ giờ để không nhấp nháy giờ chưa quy đổi.
- **Đa ngôn ngữ:** `messages.ts` lấy tiếng Anh làm gốc, tiếng Việt kiểu `Record<MessageKey, string>` nên thiếu khoá là không build được; có test kiểm tra khớp `{tham số}`; số nhiều bằng khoá `.one`.
- **Thành phần giao diện tự viết:** `Modal` (thẻ `<dialog>` gốc), `Menu` và `Select` (render qua portal với `position: fixed`, tránh lỗi định vị trong tiêu đề dùng `backdrop-filter`), `Toast`, `CopyButton` (Clipboard API, dự phòng `execCommand`).
- **Bundle:** Recharts (~390 kB) giữ ngoài bundle chính bằng `lazyWithReload` (tự tải lại một lần khi bản mới làm mất chunk cũ); có test giới hạn kích thước bundle.
- **Độ bền:** `ErrorBoundary` biến lỗi dựng và lỗi tải chunk thành trang tải lại; `index.html` tĩnh có sẵn tiêu đề/thẻ og khớp `brand.ts` vì trình thu thập và xem trước link không chạy JS.

---

## 9. Triển khai

```
git tag v* ──► CI (test backend, frontend, script sao lưu, build image Caddy)
          ──► build 2 image lên GHCR: app và caddy
          ──► deploy SSH (chỉ khi biến repo DEPLOY_ENABLED=true); chạy lại với tag cũ = quay lui
```

- **Compose prod:** chỉ Caddy mở cổng; ứng dụng chỉ `expose` trong mạng riêng, nên không gì tới được ứng dụng mà không qua giới hạn tốc độ và HTTPS của Caddy. Caddy dùng DNS động cho upstream nên nhiều bản và triển khai lại đều được nhận.
- **Cấu hình:** dịch vụ đọc từ biến môi trường; file `.env.dev`, `.env.prod` chỉ chứa giá trị mẫu (bí mật thật được đưa vào lúc triển khai). Profile `dev` mặc định; `prod` yêu cầu các biến bắt buộc (không có giá trị mặc định) và đặt Flyway không tự "baseline" CSDL không rỗng, tắt `clean`.
- **Sao lưu:** `pg_dump -Fc` hằng ngày lúc `BACKUP_AT`, mỗi bản được kiểm tra bằng `pg_restore --list`, chỉ giữ khi có bảng `flyway_schema_history`; kiểm tra sức khoẻ báo lỗi nếu quá 26 giờ không có bản mới. `backup/restore-drill.sh` phục hồi vào container tạm; `backup/selftest.sh` (25 kiểm tra) chứng minh xoay vòng, xử lý lỗi và phục hồi.

---

## 10. Các điểm cần nhớ khi mở rộng

1. Thêm **đường dẫn giao diện mới** phải đưa vào cả `ShortCodes.RESERVED` (đường dẫn một đoạn) và danh sách `@ui` của Caddyfile.
2. Mọi **đoạn mã làm thay đổi trạng thái, đích hoặc hạn của link** phải gọi `UrlCacheService.evictAfterCommit(mã)`.
3. Mọi **câu lệnh hàng loạt** bỏ qua auditing: tự đặt `updated_at`, `updated_by`.
4. Mọi **truy vấn có danh sách** phải chứng minh không N+1 bằng test đếm câu lệnh.
5. Thêm **`@ElementCollection` / `@OneToMany`** nạp cùng danh sách cha thì phải có `@BatchSize`.
6. Nếu thêm tính năng **máy chủ tự tải URL đích** thì phải kiểm tra lại IP đã phân giải (hiện chỉ kiểm tra cú pháp).
7. Thêm **lỗi mới** cần `ErrorCode` và khoá trong cả hai file i18n; thêm **chữ giao diện** cần cả hai ngôn ngữ.
8. Thêm **số liệu mới** thì khai báo trong `AppMetrics`, không viết rải rác.
9. `SHORT_CODE_SECRET` **không bao giờ đổi** sau lần triển khai đầu.
