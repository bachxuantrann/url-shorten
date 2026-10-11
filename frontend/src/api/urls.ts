import { request } from './client';
import type { PageResponse, UrlResponse, UrlStatus } from './types';

/** Các cột sắp xếp backend cho phép (UrlController whitelist). */
export type SortBy = 'createdAt' | 'clickCount' | 'expiresAt';

export interface ListParams {
  keyword?: string;
  status?: UrlStatus;
  page: number;
  size: number;
  sortBy: SortBy;
  sortDir: 'asc' | 'desc';
}

const BASE = '/api/v1/urls';
const enc = encodeURIComponent;

export function listUrls(p: ListParams): Promise<PageResponse<UrlResponse>> {
  const q = new URLSearchParams({
    page: String(p.page),
    size: String(p.size),
    sortBy: p.sortBy,
    sortDir: p.sortDir,
  });
  if (p.keyword) q.set('keyword', p.keyword);
  if (p.status) q.set('status', p.status);
  return request<PageResponse<UrlResponse>>(`${BASE}?${q}`, { auth: true });
}

export interface CreateUrlInput {
  originalUrl: string;
  alias?: string;
  password?: string;
  /** "yyyy-MM-ddTHH:mm:ss" */
  expiresAt?: string;
}

export const createUrl = (input: CreateUrlInput) =>
  request<UrlResponse>(BASE, { method: 'POST', auth: true, body: input });

/** PUT thay cả đích lẫn hạn dùng: expiresAt bỏ trống nghĩa là không hết hạn. */
export const updateUrl = (code: string, input: { originalUrl: string; expiresAt?: string }) =>
  request<UrlResponse>(`${BASE}/${enc(code)}`, { method: 'PUT', auth: true, body: input });

export const disableUrl = (code: string) => request<UrlResponse>(`${BASE}/${enc(code)}/disable`, { method: 'PUT', auth: true });

export const enableUrl = (code: string) => request<UrlResponse>(`${BASE}/${enc(code)}/enable`, { method: 'PUT', auth: true });

export const setLinkPassword = (code: string, password: string) =>
  request<UrlResponse>(`${BASE}/${enc(code)}/password`, { method: 'PUT', auth: true, body: { password } });

export const removeLinkPassword = (code: string) =>
  request<UrlResponse>(`${BASE}/${enc(code)}/password`, { method: 'DELETE', auth: true });

export const deleteUrl = (code: string) => request<void>(`${BASE}/${enc(code)}`, { method: 'DELETE', auth: true });
