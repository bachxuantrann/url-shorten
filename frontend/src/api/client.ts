import { getLanguage } from '../i18n/I18nProvider';
import type { ApiEnvelope, TokenResponse } from './types';

const ACCESS_KEY = 'accessToken';
const REFRESH_KEY = 'refreshToken';

/**
 * Lỗi từ API. UI phải rẽ nhánh theo {@link code} (ERR_xxx), không theo {@link message} (đã dịch theo Accept-Language).
 * {@link fields} là map field → lỗi khi validation thất bại (400 ERR_001).
 */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
    readonly fields: Record<string, string> = {},
    readonly retryAfterSeconds?: number,
  ) {
    super(message);
  }
}

/** Không tới được máy chủ (mất mạng, proxy chết). */
export class NetworkError extends Error {}

// Token lưu ở localStorage: đơn giản và sống sót qua F5, đổi lại XSS đọc được. Chấp nhận được vì UI không render HTML
// do người dùng cung cấp (React escape mặc định); access token ngắn hạn, refresh token bị thu hồi khi logout.
export const tokenStore = {
  access: () => safeGet(ACCESS_KEY),
  refresh: () => safeGet(REFRESH_KEY),
  set(access: string, refresh?: string) {
    safeSet(ACCESS_KEY, access);
    if (refresh) safeSet(REFRESH_KEY, refresh);
  },
  clear() {
    safeRemove(ACCESS_KEY);
    safeRemove(REFRESH_KEY);
  },
};

function safeGet(k: string): string | null {
  try {
    return localStorage.getItem(k);
  } catch {
    return null;
  }
}
function safeSet(k: string, v: string) {
  try {
    localStorage.setItem(k, v);
  } catch {
    /* bị chặn: phiên chỉ sống trong tab này */
  }
}
function safeRemove(k: string) {
  try {
    localStorage.removeItem(k);
  } catch {
    /* bỏ qua */
  }
}

let onSessionExpired: (() => void) | null = null;
/** AuthProvider đăng ký để được báo khi refresh thất bại (phiên hết hạn). */
export function setSessionExpiredHandler(handler: (() => void) | null) {
  onSessionExpired = handler;
}

// Nhiều request cùng nhận 401 chỉ được refresh một lần (refresh token so khớp chính xác ở server)
let refreshing: Promise<string | null> | null = null;

async function doRefresh(): Promise<string | null> {
  const refreshToken = tokenStore.refresh();
  if (!refreshToken) return null;
  try {
    const res = await rawFetch('/api/v1/auth/refresh-token', 'POST', { refreshToken }, null);
    if (!res.ok) return null;
    const body = await parseBody<TokenResponse>(res);
    if (!body?.data?.accessToken) return null;
    tokenStore.set(body.data.accessToken);
    return body.data.accessToken;
  } catch {
    return null;
  }
}

function refreshAccessToken(): Promise<string | null> {
  refreshing ??= doRefresh().finally(() => {
    refreshing = null;
  });
  return refreshing;
}

function rawFetch(
  path: string,
  method: string,
  body: unknown,
  token: string | null,
  accept = 'application/json',
): Promise<Response> {
  const headers: Record<string, string> = {
    Accept: accept,
    'Accept-Language': getLanguage(),
  };
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (token) headers.Authorization = `Bearer ${token}`;
  return fetch(path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
}

async function parseBody<T>(res: Response): Promise<ApiEnvelope<T> | null> {
  try {
    return (await res.json()) as ApiEnvelope<T>;
  } catch {
    return null; // body rỗng hoặc không phải JSON (vd 502 từ proxy)
  }
}

export interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'DELETE';
  body?: unknown;
  /** Gắn access token và tự refresh một lần khi gặp 401. Mặc định false (endpoint công khai). */
  auth?: boolean;
}

/** Gửi request, tự refresh một lần khi 401 (nếu auth); chưa đọc body. Mất mạng thành NetworkError. */
async function send(path: string, { method = 'GET', body, auth = false }: RequestOptions, accept?: string): Promise<Response> {
  let token = auth ? tokenStore.access() : null;
  try {
    let res = await rawFetch(path, method, body, token, accept);
    if (auth && res.status === 401 && tokenStore.refresh()) {
      token = await refreshAccessToken();
      if (token) {
        res = await rawFetch(path, method, body, token, accept);
      } else {
        tokenStore.clear();
        onSessionExpired?.();
      }
    }
    return res;
  } catch {
    throw new NetworkError();
  }
}

async function toApiError(res: Response): Promise<ApiError> {
  const envelope = await parseBody<unknown>(res);
  const retryAfter = Number(res.headers.get('Retry-After'));
  const fields =
    envelope?.data && typeof envelope.data === 'object' ? (envelope.data as Record<string, string>) : {};
  return new ApiError(
    res.status,
    envelope?.code ?? `HTTP_${res.status}`,
    envelope?.message ?? '',
    fields,
    Number.isFinite(retryAfter) && retryAfter > 0 ? retryAfter : undefined,
  );
}

export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const res = await send(path, options);
  if (!res.ok) throw await toApiError(res);
  const envelope = await parseBody<T>(res);
  return (envelope?.data ?? undefined) as T;
}

/** Tải nội dung nhị phân (ví dụ PNG của mã QR) qua cùng đường xác thực/refresh như {@link request}. */
export async function requestBlob(path: string, options: RequestOptions = {}): Promise<Blob> {
  const res = await send(path, options, 'image/png');
  if (!res.ok) throw await toApiError(res);
  return res.blob();
}
