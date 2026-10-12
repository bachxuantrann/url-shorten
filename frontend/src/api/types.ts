/** Khung trả về chung của mọi endpoint (ApiResponse ở backend). */
export interface ApiEnvelope<T> {
  success: boolean;
  code: string;
  message: string;
  data: T;
  timestamp: string;
}

export interface User {
  id: string;
  username: string;
  email: string;
  fullName: string;
  status: string;
  roles: string[];
}

export interface LoginResponse {
  accessToken: string;
  refreshToken: string;
  tokenType: string;
  expiresIn: number;
  user: User;
}

export interface TokenResponse {
  accessToken: string;
  tokenType: string;
  expiresIn: number;
}

export type UrlStatus = 'ACTIVE' | 'DISABLED' | 'EXPIRED' | 'BLOCKED' | 'DELETED';

export interface PageResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  hasNext: boolean;
  hasPrevious: boolean;
}

export type AppealStatus = 'OPEN' | 'ACCEPTED' | 'REJECTED';

/** Kháng nghị hiện tại của một link bị chặn, chủ link thấy ở chính link đó (chờ xem xét hoặc đã bị từ chối). */
export interface AppealInfo {
  status: AppealStatus;
  message: string;
  response?: string;
  createdAt: string;
}

export interface UrlResponse {
  shortCode: string;
  shortUrl: string;
  originalUrl: string;
  status: UrlStatus;
  passwordProtected: boolean;
  blockedReason: string | null;
  appeal?: AppealInfo;
  expiresAt: string | null;
  clickCount: number;
  createdAt: string;
  updatedAt: string;
}

/** Link như admin thấy: có người tạo (username, hoặc "anonymous"); link ẩn danh không có userId. */
export interface AdminUrl extends UrlResponse {
  userId?: string;
  createdBy: string;
  updatedBy: string;
}

export interface BlockedDomain {
  id: number;
  domain: string;
  reason: string | null;
  createdAt: string;
  createdBy: string;
  /** Chỉ có ngay sau khi thêm domain (số link hiện có vừa bị chặn). */
  blockedLinks?: number;
}

export type AbuseReason = 'SPAM' | 'PHISHING' | 'MALWARE' | 'ILLEGAL' | 'OTHER';

export type ReportStatus = 'OPEN' | 'RESOLVED' | 'DISMISSED';

/** Báo cáo lạm dụng gom theo link (admin): một dòng mỗi link. */
export interface AbuseSummary {
  shortCode: string;
  shortUrl: string;
  originalUrl: string;
  linkStatus: UrlStatus;
  reportCount: number;
  lastReportedAt: string;
  reasons: Partial<Record<AbuseReason, number>>;
}

/** Một báo cáo cụ thể (admin); không có thông tin nhận dạng người báo. */
export interface AbuseDetail {
  id: number;
  reason: AbuseReason;
  details?: string;
  createdAt: string;
}

/** Kháng nghị trong danh sách của admin: kèm link bị chặn, lý do chặn và chủ link. */
export interface AdminAppeal {
  id: number;
  shortCode: string;
  shortUrl: string;
  originalUrl: string;
  linkStatus: UrlStatus;
  blockedReason?: string;
  owner: string;
  message: string;
  status: AppealStatus;
  response?: string;
  createdAt: string;
  updatedAt: string;
}

/** Trạng thái kết nối tới OpenObserve (backend không bao giờ gửi thông tin đăng nhập xuống). */
export interface MonitoringStatus {
  configured: boolean;
  reachable: boolean;
  uiUrl?: string;
}

export type LogLevel = 'ERROR' | 'WARN' | 'INFO' | 'DEBUG';

export interface LogSummary {
  minutes: number;
  levels: Record<LogLevel | 'TRACE', number>;
  topExceptions: { type: string; count: number }[];
}

export interface LogEntry {
  timestamp: string;
  level?: string;
  logger?: string;
  message?: string;
  traceId?: string;
  exceptionType?: string;
  exceptionMessage?: string;
  exceptionStacktrace?: string;
}

export type UserStatus = 'ACTIVE' | 'INACTIVE' | 'BANNED';

export interface AdminUser {
  id: string;
  username: string;
  email: string;
  fullName: string;
  status: UserStatus;
  roles: string[];
  emailVerified: boolean;
  lastLoginAt?: string;
  createdAt: string;
}

export interface RegisterInput {
  username: string;
  email: string;
  password: string;
  fullName: string;
}

export interface UnlockResponse {
  originalUrl: string;
}

export interface BreakdownItem {
  name: string;
  clicks: number;
}

export interface DailyStat {
  date: string;
  /** Tổng click, gồm cả bot. */
  clicks: number;
  botClicks: number;
  uniqueVisitors: number;
}

export interface UrlStats {
  shortCode: string;
  from: string;
  to: string;
  totals: { clicks: number; botClicks: number; uniqueVisitors: number };
  daily: DailyStat[];
  countries: BreakdownItem[];
  devices: BreakdownItem[];
  browsers: BreakdownItem[];
  operatingSystems: BreakdownItem[];
}

/** Số link của người dùng theo trạng thái (link ACTIVE đã quá hạn được tính là EXPIRED). */
export interface LinkCounts {
  total: number;
  active: number;
  disabled: number;
  expired: number;
  blocked: number;
}

export interface TopLink {
  shortCode: string;
  shortUrl: string;
  originalUrl: string;
  clicks: number;
}

/** Tổng quan của mọi link chưa xoá của người dùng trong một khoảng ngày. */
export interface StatsOverview {
  from: string;
  to: string;
  links: LinkCounts;
  totals: { clicks: number; botClicks: number; uniqueVisitors: number };
  daily: DailyStat[];
  topLinks: TopLink[];
  countries: BreakdownItem[];
  devices: BreakdownItem[];
  browsers: BreakdownItem[];
  operatingSystems: BreakdownItem[];
}
