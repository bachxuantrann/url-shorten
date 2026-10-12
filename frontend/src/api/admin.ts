import { request } from './client';
import type { AbuseDetail, AbuseSummary, AdminAppeal, AdminUrl, AppealStatus, LogEntry, LogSummary, MonitoringStatus, AdminUser, BlockedDomain, PageResponse, ReportStatus, UrlStats, UrlStatus, UserStatus } from './types';
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

export const getAdminUrl = (code: string) => request<AdminUrl>(`${BASE}/urls/${enc(code)}`, { auth: true });

/** Thống kê của bất kỳ link nào (kể cả ẩn danh); from/to dạng yyyy-MM-dd, cả hai đầu đều tính, tối đa 366 ngày. */
export const getAdminStats = (code: string, from: string, to: string) =>
  request<UrlStats>(`${BASE}/urls/${enc(code)}/stats?from=${enc(from)}&to=${enc(to)}`, { auth: true });

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

/** Chuyển báo cáo của link từ trạng thái này sang trạng thái khác (mọi chiều); trả về số báo cáo đã chuyển, 0 nếu không còn báo cáo nào ở trạng thái nguồn. */
export const moveReports = (code: string, from: ReportStatus, to: ReportStatus) =>
  request<number>(`${BASE}/reports/${enc(code)}/move`, { method: 'PUT', auth: true, body: { from, to } });

export const dismissReports = (code: string) =>
  request<number>(`${BASE}/reports/${enc(code)}/dismiss`, { method: 'PUT', auth: true });

// ---------- Kháng nghị link bị chặn ----------

export const listAppeals = (p: { status: AppealStatus; page: number; size: number }) =>
  request<PageResponse<AdminAppeal>>(`${BASE}/appeals?${query({ ...p })}`, { auth: true });

/** Chấp nhận = gỡ chặn link. */
export const acceptAppeal = (id: number) => request<AdminAppeal>(`${BASE}/appeals/${id}/accept`, { method: 'PUT', auth: true });

export const rejectAppeal = (id: number, response?: string) =>
  request<AdminAppeal>(`${BASE}/appeals/${id}/reject`, { method: 'PUT', auth: true, body: response ? { response } : {} });

// ---------- Giám sát (log từ OpenObserve, backend là bên gọi) ----------

export const monitoringStatus = () => request<MonitoringStatus>(`${BASE}/monitoring/status`, { auth: true });

export const monitoringSummary = (minutes: number) => request<LogSummary>(`${BASE}/monitoring/summary?minutes=${minutes}`, { auth: true });

/** `levels` là danh sách mức cách nhau bằng dấu phẩy (ERROR,WARN,...). */
export const monitoringLogs = (p: { levels: string; keyword?: string; minutes: number; page: number; size: number }) =>
  request<PageResponse<LogEntry>>(`${BASE}/monitoring/logs?${query({ ...p })}`, { auth: true });

// ---------- Người dùng ----------

export const listAdminUsers = (p: { keyword?: string; status?: UserStatus; page: number; size: number }) =>
  request<PageResponse<AdminUser>>(`${BASE}/users?${query({ ...p })}`, { auth: true });

export const lockUser = (id: string) => request<void>(`${BASE}/users/${enc(id)}/lock`, { method: 'PUT', auth: true });

/** Cấp (ROLE_ADMIN) hoặc gỡ (ROLE_USER) quyền quản trị; có hiệu lực khi token hiện tại của họ hết hạn. */
export const changeUserRole = (id: string, role: 'ROLE_ADMIN' | 'ROLE_USER') =>
  request<AdminUser>(`${BASE}/users/${enc(id)}/role`, { method: 'PUT', auth: true, body: { role } });

export const unlockUser = (id: string) => request<void>(`${BASE}/users/${enc(id)}/unlock`, { method: 'PUT', auth: true });
