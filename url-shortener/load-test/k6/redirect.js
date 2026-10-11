// Thử tải đường redirect GET /{code}.
//
//   Mục tiêu (ngưỡng thất bại): p99 < 50 ms khi cache Redis trúng, tỉ lệ lỗi < 1%.
//
// Chạy bằng Docker (không cần cài k6), từ thư mục url-shortener/, stack scale đang chạy trên cổng 8081:
//   docker run --rm -i -e BASE_URL=http://host.docker.internal:8081 grafana/k6 run - < load-test/k6/redirect.js
//
// Biến môi trường:
//   BASE_URL   địa chỉ nginx (mặc định http://host.docker.internal:8081)
//   RATE       số request/giây (mặc định 300)
//   DURATION   thời gian chạy (mặc định 30s)
//   ADMIN_USER / ADMIN_PASS  tài khoản dùng để tạo link thử (mặc định của docker-compose.scale.yml)
//   CODE       mã link có sẵn (bỏ qua bước đăng nhập + tạo link)
//
// setup() tạo đúng MỘT link (giới hạn tạo link là 100/giờ/user), sau đó mọi request đều redirect link đó nên
// gần như toàn bộ là cache hit sau request đầu tiên.

import http from 'k6/http';
import { check, fail } from 'k6';

const BASE = __ENV.BASE_URL || 'http://host.docker.internal:8081';

export const options = {
  scenarios: {
    redirects: {
      executor: 'constant-arrival-rate',
      rate: parseInt(__ENV.RATE || '300'),
      timeUnit: '1s',
      duration: __ENV.DURATION || '30s',
      preAllocatedVUs: 100,
      maxVUs: 1000,
    },
  },
  thresholds: {
    // 302 là phản hồi đúng (xem responseCallback); mọi thứ khác tính là lỗi
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(99)<50', 'p(95)<25'],
    checks: ['rate>0.99'],
  },
};

export function setup() {
  // Dùng lại link có sẵn (CODE=...) để không phải đăng nhập: cần khi thử Redis chết, vì đăng nhập lưu refresh
  // token vào Redis và sẽ thất bại
  if (__ENV.CODE) {
    return { code: __ENV.CODE };
  }
  const user = __ENV.ADMIN_USER || 'admin';
  const pass = __ENV.ADMIN_PASS || 'Scale@Test12345';

  const login = http.post(`${BASE}/api/v1/auth/login`, JSON.stringify({ username: user, password: pass }),
    { headers: { 'Content-Type': 'application/json' } });
  if (login.status !== 200) {
    fail(`login failed: ${login.status} ${login.body}`);
  }
  const token = login.json('data.accessToken');

  const created = http.post(`${BASE}/api/v1/urls`, JSON.stringify({ originalUrl: 'https://example.com/load-test' }),
    { headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` } });
  if (created.status !== 201) {
    fail(`create link failed: ${created.status} ${created.body}`);
  }
  return { code: created.json('data.shortCode') };
}

export default function (data) {
  // redirects: 0 để đo đúng phản hồi 302 của app, không đo luôn trang đích
  const res = http.get(`${BASE}/${data.code}`, {
    redirects: 0,
    responseCallback: http.expectedStatuses(302),
  });
  check(res, {
    'status is 302': (r) => r.status === 302,
    'Location is the target': (r) => r.headers['Location'] === 'https://example.com/load-test',
  });
}
