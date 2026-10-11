import { request } from './client';
import type { AbuseDetail, AbuseSummary, AdminUrl, AdminUser, BlockedDomain, PageResponse, ReportStatus, UrlStatus, UserStatus } from './types';
import type { SortBy } from './urls';

const BASE = '/api/v1/admin';
const enc = encodeURIComponent;

function query(params: Record<string, string | number | undefined>): string {
  const q = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== '') q.set(key, String(value));
  }
  return q.toString();
}

// ---------- Link ----------

export interface AdminListParams {
  keyword?: string;
  status?: UrlStatus;
  page: number;
  size: number;
  sortBy: SortBy;
  sortDir: 'asc' | 'desc';
}

export const listAdminUrls = (p: AdminListParams) =>
  request<PageResponse<AdminUrl>>(`${BASE}/urls?${query({ ...p })}`, { auth: true });

export const blockUrl = (code: string, reason: string) =>
  request<AdminUrl>(`${BASE}/urls/${enc(code)}/block`, { method: 'PUT', auth: true, body: { reason } });

export const unblockUrl = (code: string) =>
  request<AdminUrl>(`${BASE}/urls/${enc(code)}/unblock`, { method: 'PUT', auth: true });

// ---------- Domain bị chặn ----------

export const listBlockedDomains = (p: { keyword?: string; page: number; size: number }) =>
  request<PageResponse<BlockedDomain>>(`${BASE}/blocked-domains?${query({ ...p })}`, { auth: true });

export const addBlockedDomain = (input: { domain: string; reason?: string; blockExistingLinks: boolean }) =>
  request<BlockedDomain>(`${BASE}/blocked-domains`, { method: 'POST', auth: true, body: input });

export const removeBlockedDomain = (id: number) =>
  request<void>(`${BASE}/blocked-domains/${id}`, { method: 'DELETE', auth: true });

// ---------- Báo cáo lạm dụng ----------

export const listReports = (p: { status: ReportStatus; page: number; size: number }) =>
  request<PageResponse<AbuseSummary>>(`${BASE}/reports?${query({ ...p })}`, { auth: true });

export const reportDetails = (code: string, status: ReportStatus) =>
  request<AbuseDetail[]>(`${BASE}/reports/${enc(code)}?${query({ status })}`, { auth: true });

/** Trả về số báo cáo đã đóng. */
export const resolveReports = (code: string) =>
  request<number>(`${BASE}/reports/${enc(code)}/resolve`, { method: 'PUT', auth: true });

export const dismissReports = (code: string) =>
  request<number>(`${BASE}/reports/${enc(code)}/dismiss`, { method: 'PUT', auth: true });

// ---------- Người dùng ----------

export const listAdminUsers = (p: { keyword?: string; status?: UserStatus; page: number; size: number }) =>
  request<PageResponse<AdminUser>>(`${BASE}/users?${query({ ...p })}`, { auth: true });

export const lockUser = (id: string) => request<void>(`${BASE}/users/${enc(id)}/lock`, { method: 'PUT', auth: true });

export const unlockUser = (id: string) => request<void>(`${BASE}/users/${enc(id)}/unlock`, { method: 'PUT', auth: true });
