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

export interface UrlResponse {
  shortCode: string;
  shortUrl: string;
  originalUrl: string;
  status: UrlStatus;
  passwordProtected: boolean;
  blockedReason: string | null;
  expiresAt: string | null;
  clickCount: number;
  createdAt: string;
  updatedAt: string;
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
