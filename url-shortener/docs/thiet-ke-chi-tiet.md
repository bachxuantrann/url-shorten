# TÀI LIỆU THIẾT KẾ CHI TIẾT — DỊCH VỤ RÚT GỌN LINK (Shortly)

| Mục | Nội dung |
|---|---|
| Sản phẩm | Dịch vụ rút gọn link kiểu TinyURL: tạo link ngắn, chuyển hướng, thống kê lượt click, quản trị chống lạm dụng |
| Tên hiển thị | "Shortly" (đổi ở `frontend/src/brand.ts`) |
| Phiên bản mô tả | Theo mã nguồn tại commit `dae5aac` (12/10/2026) |
| Công nghệ | Java 21, Spring Boot 4.1.1, PostgreSQL, Redis, Kafka, React 19 + Vite + TypeScript, Caddy |
| Tài liệu liên quan | `docs/deployment.md` (triển khai), `docs/observability.md` (giám sát), `docs/load-and-chaos-tests.md` (tải và sự cố), `CLAUDE.md` (ghi chú kỹ thuật cho người phát triển) |

---

## 1. Giới thiệu

### 1.1. Mục đích
Cho phép người dùng biến một đường dẫn dài thành đường dẫn ngắn dạng `https://<tên miền>/aB3xK9z`, khi truy cập sẽ được chuyển hướng tới đường dẫn gốc. Hệ thống đếm lượt click, hiển thị thống kê và có bộ công cụ cho quản trị viên để chặn link độc hại.

### 1.2. Phạm vi
- **Trong phạm vi:** tài khoản và đăng nhập, quản lý link, chuyển hướng, link có mật khẩu, mã QR, thống kê, báo cáo lạm dụng, kháng nghị link bị chặn, khu quản trị (link, domain bị chặn, người dùng, báo cáo, kháng nghị), giao diện web song ngữ Việt/Anh.
- **Ngoài phạm vi (chưa làm):** quên mật khẩu, xác minh email, tên miền riêng, API key, kiểm tra Safe Browsing, trang Điều khoản / Quyền riêng tư, bảng giá (xem mục 10).

### 1.3. Thuật ngữ
| Thuật ngữ | Ý nghĩa |
|---|---|
| Short code / mã | Phần định danh sau dấu `/` của link ngắn, 3–32 ký tự `[0-9a-zA-Z_-]` |
| Alias | Mã do người dùng tự đặt (thay cho mã sinh tự động) |
| Link ẩn danh | Link tạo khi chưa đăng nhập; không ai sở hữu, chỉ admin chặn được |
| Link bị chặn (BLOCKED) | Link bị admin vô hiệu hoá vì vi phạm; chủ link không sửa/xoá được |
| Kháng nghị | Chủ link gửi lời giải thích để xin gỡ chặn |
| Click | Một lượt truy cập link ngắn chuyển hướng thành công |

---

## 2. Kiến trúc tổng thể

```
Trình duyệt ──► Caddy (HTTPS, giới hạn tốc độ theo IP, phục vụ giao diện tĩnh)
                  ├─ /api/*  và  /{mã}  ──►  Ứng dụng Spring Boot  ──► PostgreSQL (dữ liệu chính)
                  └─ trang giao diện (SPA)                          ├─► Redis (cache link, giới hạn tốc độ, token)
                                                                    └─► Kafka (sự kiện click, bất đồng bộ)
```

- **Backend** theo lớp: `controller` → `service` (interface) / `service/impl` → `repository` (Spring Data JPA). Chuyển đổi entity ↔ DTO bằng MapStruct. Lược đồ CSDL do Flyway quản lý (`V1`…`V11`), Hibernate chỉ `validate`.
- **Frontend** là SPA React cùng origin với API (không cần CORS). Một lớp HTTP duy nhất (`src/api/client.ts`) gắn token, tự làm mới token khi gặp 401.
- **Chuyển hướng** là đường nóng: tra Redis trước, không có thì tra PostgreSQL; Redis hỏng không làm gián đoạn chuyển hướng. Việc ghi click đi qua Kafka nên không bao giờ làm chậm hay làm hỏng một lượt chuyển hướng.
- **Hạ tầng triển khai:** Docker Compose, Caddy là dịch vụ duy nhất mở cổng 80/443, dịch vụ sao lưu PostgreSQL hằng ngày, CI/CD bằng GitHub Actions.

---

## 3. Tác nhân và phân quyền

| Tác nhân | Mô tả | Quyền |
|---|---|---|
| Khách (chưa đăng nhập) | Bất kỳ ai có link | Mở link ngắn, mở khoá link có mật khẩu, tạo link ẩn danh, báo cáo lạm dụng |
| Người dùng (`ROLE_USER`) | Tài khoản đã đăng ký | Mọi quyền của khách + quản lý link của mình, thống kê, tổng quan, kháng nghị link bị chặn của mình, cập nhật hồ sơ |
| Quản trị viên (`ROLE_ADMIN`) | Có thêm `ROLE_ADMIN` | Mọi quyền của người dùng + khu quản trị `/api/v1/admin/**` |

**Cơ chế xác thực:** JWT không lưu phiên phía máy chủ. Access token sống 15 phút, refresh token 7 ngày. Mỗi lần đăng nhập có một `sessionId` riêng nên một người đăng nhập được nhiều thiết bị. Đăng xuất đưa access token vào danh sách đen (Redis) và xoá refresh token của phiên đó.

**Lưu ý về hiệu lực quyền:** vai trò nằm trong JWT nên **đổi vai trò chỉ có hiệu lực khi access token hiện tại hết hạn** (tối đa 15 phút) hoặc khi đăng nhập lại. Riêng **khoá tài khoản có hiệu lực ngay** (đánh dấu trong Redis, trả 403 `ERR_104`).

---

## 4. Danh sách chức năng

Quy ước cột: **Màn hình** là đường dẫn giao diện; **API** là endpoint; **Ai dùng**: K = khách, U = người dùng, A = admin.

| Mã | Chức năng | Ai dùng | Màn hình | API chính |
|---|---|---|---|---|
| F01 | Đăng ký | K | `/register` | `POST /auth/register` |
| F02 | Đăng nhập / đăng xuất / làm mới token | K, U | `/login` | `POST /auth/login`, `/auth/logout`, `/auth/refresh-token` |
| F03 | Hồ sơ và đổi mật khẩu | U | `/settings` | `GET/PUT /users/me`, `PUT /users/me/change-password` |
| F04 | Tạo link ẩn danh | K | `/` | `POST /public/urls` |
| F05 | Tạo và quản lý link | U | `/dashboard` | `/urls` |
| F06 | Chuyển hướng link ngắn | K | `/{mã}` | `GET /{mã}` |
| F07 | Link có mật khẩu | K, U | `/unlock/{mã}` | `PUT/DELETE /urls/{mã}/password`, `POST /public/urls/{mã}/unlock` |
| F08 | Mã QR | U | `/links/{mã}` | `GET /urls/{mã}/qr` |
| F09 | Thống kê một link | U | `/links/{mã}` | `GET /urls/{mã}/stats` |
| F10 | Tổng quan tài khoản | U | `/overview` | `GET /stats/overview` |
| F11 | Báo cáo lạm dụng | K | `/report` | `POST /public/reports` |
| F12 | Kháng nghị link bị chặn | U | `/dashboard`, `/links/{mã}` | `POST /urls/{mã}/appeal` |
| F13 | Quản trị link | A | `/admin/links`, `/admin/links/{mã}` | `/admin/urls` |
| F14 | Quản trị domain bị chặn | A | `/admin/domains` | `/admin/blocked-domains` |
| F15 | Quản trị người dùng (khoá, mở khoá, đổi vai trò) | A | `/admin/users` | `/admin/users` |
| F16 | Xử lý báo cáo lạm dụng | A | `/admin/reports` | `/admin/reports` |
| F17 | Xử lý kháng nghị | A | `/admin/appeals` | `/admin/appeals` |
| F18 | Trang trạng thái link (không tồn tại / hết hạn / bị chặn) | K | `/unavailable/{lý do}` | — |
| F19 | Giao diện: sáng/tối, Việt/Anh, múi giờ máy chủ | Tất cả | Mọi trang | `GET /public/config` |
| F20 | Giám sát log hệ thống (qua OpenObserve) | A | `/admin/monitoring` | `/admin/monitoring/status`, `/summary`, `/logs` |

Tiền tố `/api/v1` được lược bỏ ở cột API cho gọn.

### F01. Đăng ký
- **Đầu vào:** tên đăng nhập, email, mật khẩu, họ tên.
- **Xử lý:** kiểm tra trùng tên đăng nhập và email (409 `ERR_101`, `ERR_102`); mật khẩu băm bằng BCrypt; tài khoản mới có `ROLE_USER`, trạng thái hoạt động. Giao diện đăng nhập luôn sau khi đăng ký.

### F02. Đăng nhập, đăng xuất, làm mới token
- **Đăng nhập:** sai thông tin → 401 `ERR_200`; tài khoản bị khoá → 403 `ERR_104`; chưa kích hoạt → 403 `ERR_103` (trạng thái `INACTIVE` hiện **chưa có đường nào đặt được**: đăng ký tạo tài khoản `ACTIVE` ngay; nó dành cho xác minh email sau này, nên bộ lọc "Chưa kích hoạt" đã được ẩn khỏi trang Người dùng).
- **Làm mới token:** so khớp refresh token với bản lưu trong Redis (`RT:{userId}:{sessionId}`); tài khoản bị khoá vẫn nhận 403 `ERR_104` để người dùng biết lý do.
- **Giao diện:** khi gặp 401 trên request có token, tự làm mới một lần (single-flight); khi gặp 403 `ERR_104` hiện hộp thoại "Tài khoản đã bị khoá"; khi đang đăng nhập, cứ 30 giây (và khi tab hiện lại) hỏi `GET /users/me` để phát hiện bị khoá.

### F03. Hồ sơ và đổi mật khẩu
- Xem và sửa họ tên và email (email trùng bị từ chối); đổi mật khẩu cần mật khẩu cũ (sai → 400 `ERR_105`), mật khẩu mới phải khớp xác nhận (`ERR_106`).
- Trang Cài đặt còn có chọn giao diện sáng/tối và ngôn ngữ.

### F04. Tạo link ẩn danh
- Trang chủ có ô dán link. Chỉ nhận URL và hạn dùng (không alias, không mật khẩu).
- Giới hạn **10 lần/giờ theo IP** (429 kèm `Retry-After`). Link ẩn danh không có chủ (`userId` rỗng, `createdBy = anonymous`).

### F05. Tạo và quản lý link (người dùng)
- **Tạo:** URL đích, alias tuỳ chọn, mật khẩu tuỳ chọn, hạn dùng tuỳ chọn. Hạn mức **1000 link/người** (403 `ERR_501`), **100 lần tạo/giờ/người**.
- **Mã sinh tự động:** 7 ký tự Base62, sinh từ dãy số của PostgreSQL qua hoán vị có khoá bí mật (`SHORT_CODE_SECRET`, **không được đổi sau khi triển khai**), nên không trùng và không đoán được. Mã đã cấp không bao giờ được cấp lại.
- **Alias:** 3–32 ký tự, phân biệt hoa thường, không đổi được. Trùng, trùng từ khoá hệ thống, hoặc là mã của link đã xoá → 409 `ERR_407`.
- **Kiểm tra URL đích:** chỉ `http`/`https`, có host, không chứa userinfo, tối đa 2048 ký tự. Chặn trỏ về chính hệ thống (vòng lặp), localhost, IP nội bộ/dự trữ, tên miền nội bộ (`.local`, `.internal`…) → 400 `ERR_502`; domain trong danh sách chặn → 400 `ERR_503`.
- **Danh sách:** phân trang, tìm theo URL đích hoặc mã (không phân biệt hoa thường, ký tự đặc biệt được hiểu nghĩa đen), lọc trạng thái, sắp xếp theo ngày tạo / số click / hạn dùng. Màn hình rộng hiện dạng bảng, dưới 1100 px hiện dạng thẻ.
- **Thao tác:** sửa đích và hạn, tắt/bật, đặt/gỡ mật khẩu, xem QR, xoá (xoá mềm), sao chép link.
- **Trạng thái link:**

| Trạng thái | Ý nghĩa | Chuyển hướng |
|---|---|---|
| `ACTIVE` | Đang hoạt động | 302 |
| `DISABLED` | Chủ link tắt | 404 |
| `EXPIRED` | Quá hạn (kể cả `ACTIVE` đã quá `expiresAt`) | 410 |
| `BLOCKED` | Admin chặn | 403 |
| `DELETED` | Chủ link xoá | 410 |

  Link `EXPIRED` chỉ sống lại khi cập nhật hạn mới. Link `BLOCKED` chủ link không sửa, tắt, xoá hay đổi mật khẩu được. Link của người khác hoặc đã xoá luôn báo không tồn tại (404 `ERR_400`).

- **Hết hạn:** kiểm tra ngay lúc chuyển hướng (không phụ thuộc job). Job nền chạy mỗi phút chuyển các link `ACTIVE` quá hạn sang `EXPIRED`, mỗi lượt một câu SQL duy nhất, an toàn khi chạy nhiều bản (`SKIP LOCKED`).

### F06. Chuyển hướng
- `GET /{mã}` trả **302** + `Location` + `Cache-Control: no-store` (302 để mọi click đều được đếm và thay đổi có hiệu lực ngay).
- Trình duyệt (header `Accept` chứa `text/html`) gặp link chết được chuyển sang trang giao diện `/unavailable/{not-found|gone|blocked}`; link có mật khẩu chuyển sang `/unlock/{mã}`. Client API nhận mã trạng thái JSON.
- **Cache:** khóa `URL:{mã}` trong Redis, có cache âm cho mã không tồn tại; thời gian sống không vượt quá thời gian còn lại tới hạn dùng. Mọi thay đổi trạng thái/đích/hạn đều xoá cache sau khi commit.
- **Chịu lỗi:** Redis chậm quá 300 ms hoặc hỏng thì coi như cache trượt (có cầu dao ngắt mạch).
- Click chỉ được ghi với chuyển hướng thành công (không ghi cho 403/404/410, không ghi cho `HEAD`).

### F07. Link có mật khẩu
- Mật khẩu 4–64 ký tự (tối đa 72 byte UTF-8), băm BCrypt. Đặt/đổi bằng `PUT`, gỡ bằng `DELETE` (tách riêng để việc sửa đích không vô tình gỡ bảo vệ).
- Truy cập link có mật khẩu: 403 `ERR_408` (kiểm tra trạng thái/hạn trước, nên link đã tắt/hết hạn/bị chặn không lộ việc có mật khẩu). Giao diện mở trang `/unlock/{mã}`, nhập đúng thì nhận URL đích và chuyển tới (chỉ chuyển tới `http`/`https`). Sai → 403 `ERR_409`.
- **Chống dò mật khẩu:** 30 lần/15 phút theo IP; 10 lần/15 phút theo (link, IP); 100 lần/giờ theo link. Cache không bao giờ chứa URL đích hay mã băm của link có mật khẩu.

### F08. Mã QR
- Sinh tại chỗ (ZXing), không lưu: PNG của `{base-url}/{mã}`, cạnh 128–1024 px (mặc định 320), sửa lỗi mức M. Chỉ chủ link xem được. Giao diện cho xem và tải ảnh.

### F09. Thống kê một link
- **Khoảng ngày:** mặc định 30 ngày gần nhất, tối đa 366 ngày (vượt → 400 `ERR_411`); ngày tính theo múi giờ máy chủ.
- **Nội dung:** tổng click, click của bot, khách duy nhất; biểu đồ theo ngày (đủ ngày, ngày không có click = 0); top 10 quốc gia, thiết bị (gồm `BOT`), trình duyệt, hệ điều hành (chỉ tính click của người).
- **Khách duy nhất** là số chính xác theo từng ngày; tổng trong khoảng ngày là tổng các số theo ngày (người quay lại ở hai ngày được đếm hai lần, giao diện có ghi chú).
- **Quyền riêng tư:** không lưu IP thô ở đâu cả; nhận diện khách bằng HMAC-SHA256(khoá bí mật, ngày | IP | User-Agent). Quốc gia lấy từ GeoIP (MaxMind) nếu có cấu hình, không thì "Unknown".

### F10. Tổng quan tài khoản
- Gộp mọi link chưa xoá của người dùng: số link theo trạng thái, tổng click, biểu đồ theo ngày, top 5 link, và các bảng phân tích như F09. Cùng quy tắc khoảng ngày. Tài khoản chưa có link thấy lời mời "Tạo link đầu tiên".

### F11. Báo cáo lạm dụng (công khai)
- Ai cũng báo được một link: lý do `SPAM | PHISHING | MALWARE | ILLEGAL | OTHER` + mô tả tối đa 500 ký tự. Người dùng dán cả link đầy đủ, hệ thống tự rút ra mã. Lối vào ở chân trang các trang công khai và trang mở khoá.
- Giới hạn 5 lần/giờ theo IP. Mỗi người chỉ được tính một báo cáo cho một link (nhận diện qua mã băm của IP, không lưu IP); báo lại vẫn trả 201 nhưng không tạo thêm. Link không tồn tại / đã xoá → 404; link đã bị chặn → 201 và không lưu gì.

### F12. Kháng nghị link bị chặn (chủ link)
- Với link `BLOCKED`, chủ link bấm **Kháng nghị** (ở danh sách, thẻ, menu `...` và trang chi tiết), viết tối đa 500 ký tự và gửi. Giới hạn 5 lần/giờ/người.
- Mỗi link chỉ có **một kháng nghị đang chờ** (ràng buộc ở CSDL, gửi hai lần cùng lúc cũng chỉ một lần thành công → 409 `ERR_412`). Link không bị chặn → 409 `ERR_402`.
- Trạng thái hiện ngay trên link: "Đã gửi kháng nghị, đang chờ xem xét" / "Kháng nghị bị từ chối: <phản hồi>" kèm nút **Kháng nghị lại**. Khi được chấp nhận, link hoạt động trở lại và dòng trạng thái biến mất. Nếu admin chặn lại link sau đó, lời từ chối cũ không còn hiện và chủ link được gửi kháng nghị mới.

### F13. Quản trị link (admin)
- Danh sách mọi link kể cả ẩn danh: tìm theo link / mã / người tạo, lọc trạng thái, sắp xếp.
- **Chặn** (bắt buộc nhập lý do tối đa 255 ký tự) và **gỡ chặn**; cả hai xoá cache chuyển hướng. Chặn link tự đóng các báo cáo lạm dụng đang mở của link đó (đã xử lý). Gỡ chặn cũng trả lời kháng nghị đang chờ (đã chấp nhận).
- Trang chi tiết `/admin/links/{mã}` xem thống kê của bất kỳ link nào.

### F14. Quản trị domain bị chặn (admin)
- Thêm / xem / xoá domain. Chặn một domain cũng chặn mọi subdomain; tuỳ chọn **chặn luôn các link hiện có** của domain (mặc định bật, báo số link đã chặn). Xoá domain khỏi danh sách **không** gỡ chặn các link đã bị chặn.

### F15. Quản trị người dùng (admin)
- Danh sách có tìm (tên đăng nhập, email, họ tên), lọc trạng thái.
- **Khoá / mở khoá** tài khoản (có hiệu lực ngay). Không tự khoá chính mình ở giao diện.
- **Đổi vai trò:** cấp hoặc gỡ `ROLE_ADMIN` (mọi người dùng giữ `ROLE_USER`). Không tự đổi vai trò của mình (403 `ERR_107`), không gỡ admin đang hoạt động cuối cùng (409 `ERR_108`). Hiệu lực khi token hết hạn hoặc đăng nhập lại.

### F16. Xử lý báo cáo lạm dụng (admin)
- Ba tab: Chờ xử lý / Đã xử lý / Đã bỏ qua. Mỗi **link** là một thẻ (số báo cáo, số báo cáo theo lý do, lần báo gần nhất); bung thẻ xem từng báo cáo (tối đa 100, không có thông tin người báo).
- Thao tác: **Chặn link**, **Đánh dấu đã xử lý**, **Bỏ qua** (không phải lạm dụng); đóng mọi báo cáo đang mở của link bằng một câu lệnh.
- **Chuyển giữa ba tab theo mọi chiều** (`PUT /admin/reports/{mã}/move`): kéo thẻ thả lên nút tab, hoặc dùng menu "Chuyển tới…" trên thẻ (dành cho màn hình cảm ứng và bàn phím). Chỉ chuyển các báo cáo đang ở tab nguồn, không đổi trạng thái của link; nếu người khác vừa chuyển trước thì báo và làm mới danh sách.

### F17. Xử lý kháng nghị (admin)
- Ba tab: Chờ xem xét (cũ nhất trước) / Đã chấp nhận / Đã từ chối. Mỗi thẻ có link, lý do bị chặn, chủ link, nội dung kháng nghị.
- **Chấp nhận và gỡ chặn:** gỡ chặn link, xoá cache và đóng kháng nghị trong **một transaction**. **Từ chối:** kèm lời phản hồi tuỳ chọn tối đa 255 ký tự, link vẫn bị chặn.
- Hai admin bấm cùng lúc thì chỉ một người thành công, người kia nhận 409 `ERR_414`. Id không có → 404 `ERR_413`.

### F18. Trang trạng thái
- `/unavailable/not-found`, `/unavailable/gone`, `/unavailable/blocked` là nơi trình duyệt được chuyển tới khi mở link chết, kèm trang 404 của giao diện.

### F20. Giám sát log hệ thống (admin)
- Mục **Giám sát** ở khu Quản trị. Backend gọi OpenObserve (API tìm kiếm, xác thực Basic) rồi trả log cho admin; trình duyệt không bao giờ thấy thông tin đăng nhập OpenObserve.
- **Nội dung:** huy hiệu kết nối ("Đã kết nối" / "OpenObserve không phản hồi"), nút **Mở OpenObserve** (tab mới) để xem trace và metric; số dòng log theo mức Lỗi / Cảnh báo / Thông tin / Gỡ lỗi (bấm thẻ để bật/tắt lọc, luôn còn ít nhất một mức; mặc định Lỗi + Cảnh báo); top exception gặp nhiều nhất; danh sách log mới nhất trước, phân trang 25 dòng; bung từng dòng xem loại exception, stacktrace và mã trace (có nút sao chép).
- **Bộ lọc:** khoảng 15 phút / 1 giờ / 6 giờ / 24 giờ; tìm trong nội dung log (không phân biệt hoa thường, tối đa 100 ký tự); tự làm mới mỗi 10 giây.
- **Cấu hình:** `OPENOBSERVE_URL`, `OPENOBSERVE_UI_URL`, `OPENOBSERVE_EMAIL`, `OPENOBSERVE_PASSWORD`. Chưa cấu hình (mặc định ở prod) thì trang báo cách thiết lập và không gọi gì. OpenObserve lỗi → 502 `ERR_601`, chưa cấu hình → 503 `ERR_600`.
- **An toàn:** mức log và khoảng thời gian chỉ nhận giá trị trong danh sách cho phép; từ khoá được thoát dấu nháy và so khớp chuỗi con (không dùng mẫu LIKE); chuỗi dài bị cắt.

### F19. Giao diện chung
- Sáng/tối, Việt/Anh (tiếng Anh là nguồn; tiếng Việt thiếu khoá thì không build được), số nhiều đúng ("1 link", "2 links").
- Hiển thị giờ theo múi giờ máy chủ (API trả `LocalDateTime` theo giờ máy chủ; giao diện tải múi giờ từ `GET /public/config` và quy đổi).
- Menu, danh sách chọn, hộp thoại bàn phím đầy đủ; bố cục thích ứng tới 375 px; `ErrorBoundary` thay màn hình trắng bằng trang tải lại khi có lỗi hoặc khi bản mới làm mất chunk cũ.

---

## 5. Sơ đồ chuyển trạng thái

**Link**
```
 tạo ─► ACTIVE ⇄ DISABLED          (chủ link tắt / bật)
        ACTIVE ─► EXPIRED          (quá hạn; job nền hoặc kiểm tra lúc chuyển hướng)
        EXPIRED ─► ACTIVE          (cập nhật hạn mới)
        ACTIVE|DISABLED|EXPIRED ─► BLOCKED   (admin chặn, hoặc chặn domain)
        BLOCKED ─► ACTIVE          (admin gỡ chặn, hoặc chấp nhận kháng nghị)
        mọi trạng thái trừ BLOCKED ─► DELETED (chủ link xoá, xoá mềm, mã không dùng lại)
```

**Kháng nghị:** `OPEN` → `ACCEPTED` (gỡ chặn link) | `REJECTED` (kèm phản hồi). Từ `REJECTED` chủ link được gửi kháng nghị mới.

**Báo cáo lạm dụng:** `OPEN` → `RESOLVED` | `DISMISSED`.

---

## 6. Thiết kế dữ liệu

| Bảng | Mục đích | Cột / ràng buộc chính |
|---|---|---|
| `users` | Tài khoản | `id` UUID, `username` và `email` duy nhất, `status` (ACTIVE / INACTIVE / BANNED), `email_verified`, `last_login_at` |
| `user_roles` | Vai trò (bảng phụ) | `user_id`, `role` (`ROLE_USER`, `ROLE_ADMIN`) |
| `urls` | Link ngắn | `id` BIGSERIAL, `short_code` duy nhất (tối đa 32), `user_id` (rỗng nếu ẩn danh), `original_url`, `status`, `expires_at`, `password_hash`, `blocked_reason`, `deleted_at`, `click_count` |
| `blocked_domains` | Danh sách domain bị chặn | `domain`, `reason` |
| `url_clicks` | Mỗi click | Khoá `(event_id, clicked_at)`; **phân vùng theo tháng** (`url_clicks_yYYYYmMM` + phân vùng mặc định); thiết bị, trình duyệt, hệ điều hành, quốc gia, mã băm khách |
| `url_daily_stats` | Tổng hợp theo ngày | Duy nhất `(url_id, stat_date)`: `clicks`, `bot_clicks`, `unique_visitors` (giữ vĩnh viễn) |
| `url_daily_visitors` | Khách duy nhất theo ngày | `(url_id, stat_date, visitor_hash)` để đếm chính xác |
| `abuse_reports` | Báo cáo lạm dụng | Duy nhất `(url_id, reporter_hash)`; `reason`, `details`, `status` |
| `link_appeals` | Kháng nghị | `url_id`, `user_id`, `message` (≤500), `status`, `response` (≤255); chỉ mục duy nhất một phần `url_id` khi `status = 'OPEN'` |
| `short_code_seq` | Dãy số sinh mã | Dãy PostgreSQL |

Bảng có kiểm toán (`created_at`, `updated_at`, `created_by`, `updated_by`) kế thừa `BaseEntity`. **Dữ liệu trong Redis:** `URL:{mã}` (cache chuyển hướng), `RT:{userId}:{sessionId}` (refresh token), `BL:{jti}` (access token đã đăng xuất), `LOCK:{userId}` (tài khoản bị khoá), `RL:{nhóm}:{chủ thể}` (bộ đếm giới hạn tốc độ).

---

## 7. Luồng xử lý chính

**7.1. Click một link ngắn**
1. Caddy áp giới hạn 600 request/phút/IP rồi chuyển cho ứng dụng.
2. Tra Redis `URL:{mã}`; trượt thì tra PostgreSQL rồi nạp lại cache.
3. Kiểm tra trạng thái và hạn → trả 302, hoặc 404/410/403/`ERR_408` (hoặc chuyển trang giao diện nếu là trình duyệt).
4. Nếu 302: đẩy một sự kiện click vào hàng đợi trong bộ nhớ rồi trả lời ngay; một luồng riêng gửi lên Kafka (khóa = mã). Hàng đợi đầy thì bỏ sự kiện chứ không làm chậm chuyển hướng.
5. Bộ tiêu thụ Kafka nhận theo lô, phân tích User-Agent, ghi **một câu SQL** vào `url_clicks`, `url_daily_stats`, `url_daily_visitors` và cộng `urls.click_count`; sự kiện nhận lại không bị đếm hai lần (`ON CONFLICT DO NOTHING`). Lô lỗi hạ tầng được thử lại, sau đó chuyển sang topic `url-clicks.DLT`.

**7.2. Kháng nghị link bị chặn**
Chủ link gửi kháng nghị → xuất hiện ở `/admin/appeals` → admin chấp nhận (link `ACTIVE`, cache xoá, kháng nghị `ACCEPTED`) hoặc từ chối (kèm phản hồi) → chủ link thấy kết quả ngay trên link của mình.

**7.3. Xử lý báo cáo lạm dụng**
Khách báo → admin xem theo link → chặn link (các báo cáo thành "đã xử lý") hoặc bỏ qua.

---

## 8. Yêu cầu phi chức năng

| Nhóm | Yêu cầu / thực hiện |
|---|---|
| Hiệu năng | Mục tiêu p99 chuyển hướng khi trúng cache < 50 ms; đo được 14–16 ms ở 300–1000 req/s (đã làm nóng JVM) |
| Chịu lỗi | Cầu dao ngắt mạch cho Redis (cache, giới hạn tốc độ) và Kafka; Redis hỏng → vẫn chuyển hướng bằng PostgreSQL; Kafka hỏng → vẫn chuyển hướng (click gửi lúc đó bị mất, chưa có outbox) |
| Chống lạm dụng | Giới hạn tốc độ nhiều tầng: Caddy theo IP (API 240, đăng nhập 15, mở khoá 30, tạo ẩn danh 20, chuyển hướng 600 mỗi phút) và Redis theo nghiệp vụ (xem các mục F04–F12) |
| Bảo mật | BCrypt, JWT, không lưu IP thô, HTTPS qua Caddy, ứng dụng không mở cổng ra ngoài, header `X-Forwarded-For` chỉ tin từ mạng riêng, chống SSRF ở mức cú pháp (không phân giải DNS) |
| Dữ liệu | Click giữ 13 tháng (xoá phân vùng cũ); thống kê tổng hợp theo ngày giữ vĩnh viễn; đối soát hằng ngày giữa `click_count` và bảng thống kê |
| Quan sát | Log, trace, metric qua OTLP (OpenObserve); mỗi lượt chuyển hướng là một trace xuyên Redis, PostgreSQL, Kafka; số liệu tuỳ biến (độ trễ chuyển hướng, tỷ lệ trúng cache, lỗi Kafka, độ trễ consumer) |
| Sao lưu | `pg_dump` hằng ngày, giữ 7 bản ngày + 4 bản tuần, mỗi bản được kiểm tra; có kịch bản phục hồi thử |
| Tắt máy | Tắt êm 30 giây, xả nốt hàng đợi click trước khi thoát |
| Kiểm thử | 538 test backend (tích hợp bằng Testcontainers) và 333 test frontend |
| i18n | Toàn bộ thông báo lỗi và giao diện có tiếng Việt và tiếng Anh; ngôn ngữ chọn theo `Accept-Language` / cài đặt của người dùng |

---

## 9. Bảng mã lỗi chính

| Mã | HTTP | Ý nghĩa |
|---|---|---|
| `ERR_001` / `002` / `003` | 400 / 404 / 405 | Dữ liệu sai / đường dẫn không tồn tại / sai phương thức |
| `ERR_100`–`106` | nhiều | Người dùng: không tồn tại, trùng tên, trùng email, chưa kích hoạt, bị khoá (`104`), sai mật khẩu cũ, mật khẩu không khớp |
| `ERR_107` | 403 | Không được tự đổi vai trò của mình |
| `ERR_108` | 409 | Phải còn ít nhất một quản trị viên đang hoạt động |
| `ERR_200`–`205` | 401 | Xác thực và token |
| `ERR_300` / `301` | 401 / 403 | Chưa đăng nhập / không đủ quyền |
| `ERR_400` | 404 | Link không tồn tại |
| `ERR_401` / `406` | 403 | Link bị chặn (chỉnh sửa / truy cập) |
| `ERR_402` | 409 | Thao tác không hợp lệ với trạng thái hiện tại của link |
| `ERR_403` / `405` | 400 / 410 | Hết hạn / đã bị xoá hoặc hết hạn khi truy cập |
| `ERR_404` | 400 | URL không hợp lệ |
| `ERR_407` | 409 | Alias không dùng được |
| `ERR_408` / `409` | 403 | Link cần mật khẩu / sai mật khẩu |
| `ERR_410` | 400 | Mật khẩu link không hợp lệ |
| `ERR_411` | 400 | Khoảng ngày thống kê không hợp lệ |
| `ERR_412` | 409 | Link đã có kháng nghị đang chờ |
| `ERR_413` / `414` | 404 / 409 | Không có kháng nghị / kháng nghị đã được xem xét |
| `ERR_500` | 429 | Vượt giới hạn tốc độ (kèm `Retry-After`) |
| `ERR_501` | 403 | Hết hạn mức link |
| `ERR_502` / `503` | 400 | Đích không an toàn / domain bị chặn |
| `ERR_504`–`506` | 409/404/400 | Domain bị chặn: đã tồn tại / không có / không hợp lệ |
| `ERR_600` / `601` | 503 / 502 | Giám sát: chưa cấu hình OpenObserve / không đọc được log |
| `ERR_999` | 500 | Lỗi hệ thống |

Mọi lỗi dùng chung một khuôn `{success, code, message, data?, timestamp}`; `message` đã được dịch theo ngôn ngữ yêu cầu. Giao diện dựa vào `code`, không dựa vào `message`.

---

## 10. Hạn chế và việc chưa làm

- **Chưa có:** quên mật khẩu và xác minh email (cần nhà cung cấp SMTP), tên miền riêng, API key, Safe Browsing, trang Điều khoản/Quyền riêng tư và bảng giá (cần nội dung thật).
- **Thông báo:** admin phải tự mở trang để thấy báo cáo/kháng nghị mới; chủ link chỉ thấy kết quả kháng nghị khi mở lại link của mình.
- **Dọn dữ liệu:** báo cáo lạm dụng và kháng nghị chưa có job xoá dữ liệu cũ.
- **Click:** click gửi lúc Kafka hỏng bị mất; chưa có nơi đọc topic `url-clicks.DLT` hay cảnh báo khi đối soát lệch.
- **GeoIP:** cần gắn file `GeoLite2-Country.mmdb` của MaxMind, nếu không quốc gia luôn là "Unknown".
- **Triển khai thật:** chưa chạy thử HTTPS Let's Encrypt, hai workflow GitHub, deploy/rollback qua SSH và phục hồi sao lưu trên máy chủ thật; sao lưu chưa được chép ra ngoài VPS; một bản ứng dụng nên mỗi lần cập nhật có khoảng 60–90 giây lỗi 502.
- **Vai trò:** đổi vai trò không có hiệu lực ngay (xem mục 3).
