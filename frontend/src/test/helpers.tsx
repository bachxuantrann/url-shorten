import { render } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { vi } from 'vitest';
import { App } from '../App';
import { tokenStore } from '../api/client';
import type { UrlResponse } from '../api/types';
import { AuthProvider } from '../auth/AuthProvider';
import { resetServerZoneCache } from '../hooks/useServerTime';
import { browserZone } from '../utils/timezone';
import { ToastProvider } from '../components/ui/Toast';
import { I18nProvider } from '../i18n/I18nProvider';
import { ThemeProvider } from '../theme/ThemeProvider';

// ---------- Response giả ----------

export const json = (status: number, body: unknown, headers: Record<string, string> = {}) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json', ...headers } });
export const ok = (data: unknown) => json(200, { success: true, code: 'SUCCESS', message: 'ok', data });
export const created = (data: unknown) => json(201, { success: true, code: 'SUCCESS', message: 'ok', data });
export const fail = (status: number, code: string, message: string, data: unknown = null, headers: Record<string, string> = {}) =>
  json(status, { success: false, code, message, data }, headers);

export const USER = { id: '1', username: 'alice', fullName: 'Alice Nguyen', email: 'alice@example.com', status: 'ACTIVE', roles: ['ROLE_USER'] };
export const me = () => ok(USER);

export const link = (code: string, extra: Partial<UrlResponse> = {}): UrlResponse => ({
  shortCode: code,
  shortUrl: `https://sho.rt/${code}`,
  originalUrl: `https://example.com/${code}`,
  status: 'ACTIVE',
  passwordProtected: false,
  blockedReason: null,
  expiresAt: null,
  clickCount: 0,
  createdAt: '2026-10-01T10:00:00',
  updatedAt: '2026-10-01T10:00:00',
  ...extra,
});

export const pageOf = (content: UrlResponse[], extra: object = {}) => ({
  content,
  page: 0,
  size: 10,
  totalElements: content.length,
  totalPages: 1,
  hasNext: false,
  hasPrevious: false,
  ...extra,
});

// ---------- Máy chủ giả ----------

export interface Call {
  method: string;
  path: string;
  query: URLSearchParams;
  body: unknown;
  headers: Record<string, string>;
}

export const fetchMock = vi.fn();
export let calls: Call[] = [];

/** Chuẩn bị mỗi test: xoá lịch sử gọi, cài fetch giả, đặt ngôn ngữ tiếng Việt cho đoán chữ ổn định. */
export function resetServer(language: 'vi' | 'en' = 'vi') {
  localStorage.setItem('lang', language);
  resetServerZoneCache();
  calls = [];
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
}

/** Máy chủ giả rẽ nhánh theo "METHOD /path"; mọi request được ghi lại để assert. Request không khai báo làm test thất bại rõ ràng. */
export function serve(extraRoutes: Record<string, (call: Call) => Response>) {
  // Múi giờ máy chủ mặc định = múi giờ của máy chạy test: không quy đổi, nên kết quả không phụ thuộc máy; test về múi giờ ghi đè route này
  const routes: Record<string, (call: Call) => Response> = { 'GET /api/v1/public/config': () => ok({ timezone: browserZone() }), ...extraRoutes };
  fetchMock.mockImplementation(async (url: string, init: RequestInit = {}) => {
    const u = new URL(url, 'http://x');
    const call: Call = {
      method: init.method ?? 'GET',
      path: u.pathname,
      query: u.searchParams,
      body: init.body ? JSON.parse(String(init.body)) : undefined,
      headers: (init.headers ?? {}) as Record<string, string>,
    };
    calls.push(call);
    const handler = routes[`${call.method} ${call.path}`];
    if (!handler) throw new Error(`unexpected request ${call.method} ${url}`);
    return handler(call);
  });
}

export const callsTo = (method: string, path: string) => calls.filter((c) => c.method === method && c.path === path);
export const listCalls = () => callsTo('GET', '/api/v1/urls');

// ---------- Render ----------

/** Dựng toàn bộ ứng dụng tại một đường dẫn. `authed` đặt sẵn token để phiên được khôi phục qua GET /users/me. */
export function renderApp(path: string, { authed = false }: { authed?: boolean } = {}) {
  if (authed) tokenStore.set('at', 'rt');
  return render(
    <I18nProvider>
      <ThemeProvider>
        <ToastProvider>
          <AuthProvider>
            <MemoryRouter initialEntries={[path]}>
              <App />
            </MemoryRouter>
          </AuthProvider>
        </ToastProvider>
      </ThemeProvider>
    </I18nProvider>,
  );
}

/** Mô phỏng media query (jsdom không có matchMedia): trả matches theo hàm đã cho. */
export function mockMatchMedia(matches: (query: string) => boolean) {
  vi.stubGlobal('matchMedia', (query: string) => ({
    matches: matches(query),
    media: query,
    addEventListener: () => {},
    removeEventListener: () => {},
  }));
}
