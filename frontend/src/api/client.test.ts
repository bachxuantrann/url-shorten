import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError, NetworkError, request, setSessionExpiredHandler, tokenStore } from './client';

function json(status: number, body: unknown, headers: Record<string, string> = {}) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json', ...headers } });
}
const ok = (data: unknown) => json(200, { success: true, code: 'SUCCESS', message: 'ok', data });

/** Chờ promise thất bại và trả về lỗi (đã ép kiểu ApiError). */
async function fail(p: Promise<unknown>): Promise<ApiError> {
  try {
    await p;
  } catch (e) {
    return e as ApiError;
  }
  throw new Error("expected the request to fail");
}

describe('api client', () => {
  const fetchMock = vi.fn();

  beforeEach(() => {
    fetchMock.mockReset();
    vi.stubGlobal('fetch', fetchMock);
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    setSessionExpiredHandler(null);
  });

  it('gửi Accept-Language và trả về data', async () => {
    localStorage.setItem('lang', 'en');
    fetchMock.mockResolvedValue(ok({ a: 1 }));

    const data = await request<{ a: number }>('/api/v1/x');

    expect(data).toEqual({ a: 1 });
    expect(fetchMock.mock.calls[0][1].headers['Accept-Language']).toBe('en');
  });

  it('chuyển lỗi thành ApiError với code, field và Retry-After', async () => {
    fetchMock.mockResolvedValue(
      json(429, { success: false, code: 'ERR_900', message: 'chậm lại', data: null }, { 'Retry-After': '42' }),
    );

    const err = await fail(request('/api/v1/x'));

    expect(err).toBeInstanceOf(ApiError);
    expect(err.status).toBe(429);
    expect(err.code).toBe('ERR_900');
    expect(err.retryAfterSeconds).toBe(42);
  });

  it('lấy lỗi theo field khi validation thất bại', async () => {
    fetchMock.mockResolvedValue(
      json(400, { success: false, code: 'ERR_001', message: 'sai', data: { username: 'quá ngắn' } }),
    );

    const err = await fail(request('/api/v1/x', { method: 'POST', body: {} }));

    expect(err.fields).toEqual({ username: 'quá ngắn' });
  });

  it('body không phải JSON (502 từ proxy) vẫn thành ApiError', async () => {
    fetchMock.mockResolvedValue(new Response('<html>Bad Gateway</html>', { status: 502 }));

    const err = await fail(request('/api/v1/x'));

    expect(err).toBeInstanceOf(ApiError);
    expect(err.code).toBe('HTTP_502');
  });

  it('mất mạng thành NetworkError', async () => {
    fetchMock.mockRejectedValue(new TypeError('Failed to fetch'));

    await expect(request('/api/v1/x')).rejects.toBeInstanceOf(NetworkError);
  });

  it('401 thì refresh một lần rồi gọi lại với token mới', async () => {
    tokenStore.set('old', 'refresh-1');
    fetchMock
      .mockResolvedValueOnce(json(401, { success: false, code: 'ERR_401', message: '', data: null }))
      .mockResolvedValueOnce(ok({ accessToken: 'new' }))
      .mockResolvedValueOnce(ok({ me: true }));

    const data = await request('/api/v1/users/me', { auth: true });

    expect(data).toEqual({ me: true });
    expect(tokenStore.access()).toBe('new');
    expect(fetchMock.mock.calls[2][1].headers.Authorization).toBe('Bearer new');
  });

  it('nhiều request cùng 401 chỉ refresh đúng một lần', async () => {
    tokenStore.set('old', 'refresh-1');
    fetchMock.mockImplementation(async (url: string, init: RequestInit) => {
      if (url.endsWith('/refresh-token')) return ok({ accessToken: 'new' });
      const auth = (init.headers as Record<string, string>).Authorization;
      return auth === 'Bearer new' ? ok({ done: true }) : json(401, { success: false, code: 'ERR_401', message: '', data: null });
    });

    await Promise.all([request('/api/v1/a', { auth: true }), request('/api/v1/b', { auth: true }), request('/api/v1/c', { auth: true })]);

    const refreshCalls = fetchMock.mock.calls.filter(([u]) => String(u).endsWith('/refresh-token'));
    expect(refreshCalls).toHaveLength(1);
  });

  it('refresh thất bại thì xoá token và báo phiên hết hạn', async () => {
    tokenStore.set('old', 'refresh-1');
    const expired = vi.fn();
    setSessionExpiredHandler(expired);
    fetchMock
      .mockResolvedValueOnce(json(401, { success: false, code: 'ERR_401', message: '', data: null }))
      .mockResolvedValueOnce(json(401, { success: false, code: 'ERR_401', message: '', data: null }));

    const err = await fail(request('/api/v1/users/me', { auth: true }));

    expect(err).toBeInstanceOf(ApiError);
    expect(tokenStore.access()).toBeNull();
    expect(tokenStore.refresh()).toBeNull();
    expect(expired).toHaveBeenCalledOnce();
  });

  it('endpoint công khai (login) không refresh khi 401', async () => {
    tokenStore.set('old', 'refresh-1');
    fetchMock.mockResolvedValue(json(401, { success: false, code: 'ERR_101', message: 'sai mật khẩu', data: null }));

    const err = await fail(request('/api/v1/auth/login', { method: 'POST', body: {} }));

    expect(err.code).toBe('ERR_101');
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
});
