import type { ApiEnvelope, LoginData, Page, UrlItem, UrlStatus, User } from './types'

const BASE = '/api/v1'
const REFRESH_KEY = 'rt'

/** Lỗi trả về từ API (hoặc lỗi mạng, status = 0). */
export class ApiError extends Error {
  constructor(
    message: string,
    public readonly code: string,
    public readonly status: number,
  ) {
    super(message)
  }
}

// Access token chỉ nằm trong bộ nhớ; refresh token nằm ở localStorage để giữ đăng nhập qua lần tải lại trang.
let accessToken: string | null = null
let refreshing: Promise<boolean> | null = null
let onSessionLost: () => void = () => {}

export function setSessionLostHandler(fn: () => void) {
  onSessionLost = fn
}

export function hasStoredSession(): boolean {
  try {
    return !!localStorage.getItem(REFRESH_KEY)
  } catch {
    return false
  }
}

function storeRefresh(token: string | null) {
  try {
    if (token) localStorage.setItem(REFRESH_KEY, token)
    else localStorage.removeItem(REFRESH_KEY)
  } catch {
    /* trình duyệt chặn storage: bỏ qua, người dùng chỉ phải đăng nhập lại khi tải lại trang */
  }
}

function readRefresh(): string | null {
  try {
    return localStorage.getItem(REFRESH_KEY)
  } catch {
    return null
  }
}

/** Chuyển một query object thành chuỗi, bỏ các giá trị rỗng. */
export function toQuery(params: Record<string, string | number | undefined | null>): string {
  const qs = new URLSearchParams()
  for (const [k, v] of Object.entries(params)) {
    if (v !== undefined && v !== null && v !== '') qs.set(k, String(v))
  }
  const s = qs.toString()
  return s ? `?${s}` : ''
}

async function parse<T>(res: Response): Promise<T> {
  let body: ApiEnvelope<T> | null = null
  try {
    body = (await res.json()) as ApiEnvelope<T>
  } catch {
    /* không phải JSON */
  }
  if (!res.ok || (body && body.success === false)) {
    throw new ApiError(body?.message ?? `Lỗi ${res.status}`, body?.code ?? 'ERR_HTTP', res.status)
  }
  return body?.data as T
}

async function raw(path: string, init: RequestInit, auth: boolean): Promise<Response> {
  const headers = new Headers(init.headers)
  if (init.body && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json')
  if (auth && accessToken) headers.set('Authorization', `Bearer ${accessToken}`)
  try {
    return await fetch(BASE + path, { ...init, headers })
  } catch {
    throw new ApiError('Không kết nối được máy chủ', 'ERR_NETWORK', 0)
  }
}

/** Đổi refresh token lấy access token mới. Nhiều request 401 cùng lúc chỉ gọi một lần. */
async function refresh(): Promise<boolean> {
  const rt = readRefresh()
  if (!rt) return false
  refreshing ??= (async () => {
    try {
      const res = await raw('/auth/refresh-token', { method: 'POST', body: JSON.stringify({ refreshToken: rt }) }, false)
      const data = await parse<{ accessToken: string }>(res)
      accessToken = data.accessToken
      return true
    } catch {
      accessToken = null
      storeRefresh(null)
      return false
    } finally {
      refreshing = null
    }
  })()
  return refreshing
}

async function request<T>(path: string, init: RequestInit = {}, auth = true): Promise<T> {
  let res = await raw(path, init, auth)
  if (res.status === 401 && auth) {
    if (await refresh()) {
      res = await raw(path, init, auth)
    } else {
      onSessionLost()
    }
  }
  return parse<T>(res)
}

const json = (method: string, body?: unknown): RequestInit => ({
  method,
  body: body === undefined ? undefined : JSON.stringify(body),
})

/** `datetime-local` ("2026-10-11T08:30") -> LocalDateTime của server; rỗng -> undefined. */
export function toServerDateTime(v: string): string | undefined {
  if (!v) return undefined
  return v.length === 16 ? `${v}:00` : v
}

export const api = {
  async login(username: string, password: string): Promise<User> {
    const data = await request<LoginData>('/auth/login', json('POST', { username, password }), false)
    accessToken = data.accessToken
    storeRefresh(data.refreshToken)
    return data.user
  },

  register: (body: { username: string; email: string; password: string; fullName: string }) =>
    request<User>('/auth/register', json('POST', body), false),

  /** Khôi phục phiên từ refresh token đã lưu; trả null nếu không có/hết hạn. */
  async restore(): Promise<User | null> {
    if (!(await refresh())) return null
    return request<User>('/users/me')
  },

  async logout(): Promise<void> {
    try {
      await request<void>('/auth/logout', json('POST'))
    } catch {
      /* dù server lỗi vẫn xoá phiên phía client */
    }
    accessToken = null
    storeRefresh(null)
  },

  createAnonymous: (body: { originalUrl: string; expiresAt?: string }) =>
    request<UrlItem>('/public/urls', json('POST', body), false),

  create: (body: { originalUrl: string; alias?: string; password?: string; expiresAt?: string }) =>
    request<UrlItem>('/urls', json('POST', body)),

  list: (p: { keyword?: string; status?: UrlStatus | ''; page: number; size: number; sortBy: string; sortDir: string }) =>
    request<Page<UrlItem>>('/urls' + toQuery(p)),

  update: (code: string, body: { originalUrl: string; expiresAt?: string }) =>
    request<UrlItem>(`/urls/${encodeURIComponent(code)}`, json('PUT', body)),

  disable: (code: string) => request<UrlItem>(`/urls/${encodeURIComponent(code)}/disable`, json('PUT')),
  enable: (code: string) => request<UrlItem>(`/urls/${encodeURIComponent(code)}/enable`, json('PUT')),
  remove: (code: string) => request<void>(`/urls/${encodeURIComponent(code)}`, json('DELETE')),

  setPassword: (code: string, password: string) =>
    request<UrlItem>(`/urls/${encodeURIComponent(code)}/password`, json('PUT', { password })),
  removePassword: (code: string) =>
    request<UrlItem>(`/urls/${encodeURIComponent(code)}/password`, json('DELETE')),

  /** QR là PNG, cần header Authorization nên tải bằng fetch rồi tạo object URL. */
  async qr(code: string, size = 320): Promise<string> {
    const path = `/urls/${encodeURIComponent(code)}/qr?size=${size}`
    let res = await raw(path, {}, true)
    if (res.status === 401 && (await refresh())) res = await raw(path, {}, true)
    if (!res.ok) throw new ApiError('Không tải được mã QR', 'ERR_HTTP', res.status)
    return URL.createObjectURL(await res.blob())
  },
}
