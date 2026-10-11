import { request, requestBlob } from './client';
import type { UnlockResponse, UrlResponse, UrlStats } from './types';

const enc = encodeURIComponent;

export const getUrl = (code: string) => request<UrlResponse>(`/api/v1/urls/${enc(code)}`, { auth: true });

/** from/to dạng yyyy-MM-dd, cả hai đầu đều tính; tối đa 366 ngày (server trả ERR_411 nếu vượt). */
export const getStats = (code: string, from: string, to: string) =>
  request<UrlStats>(`/api/v1/urls/${enc(code)}/stats?from=${enc(from)}&to=${enc(to)}`, { auth: true });

/** PNG của mã QR; endpoint cần Bearer token nên không dùng được <img src> trực tiếp. */
export const getQrPng = (code: string, size = 320) =>
  requestBlob(`/api/v1/urls/${enc(code)}/qr?size=${size}`, { auth: true });

/** Nhập mật khẩu của link được bảo vệ (công khai, có giới hạn thử); đúng thì trả về đích để điều hướng. */
export const unlockLink = (code: string, password: string) =>
  request<UnlockResponse>(`/api/v1/public/urls/${enc(code)}/unlock`, { method: 'POST', body: { password } });
