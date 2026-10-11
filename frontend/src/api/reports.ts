import { request } from './client';
import type { AbuseReason } from './types';

/** Lý do người báo có thể chọn (khớp AbuseReasonEnum ở backend). */
export const ABUSE_REASONS: AbuseReason[] = ['SPAM', 'PHISHING', 'MALWARE', 'ILLEGAL', 'OTHER'];

/** Báo cáo lạm dụng một link (công khai, không cần đăng nhập, có giới hạn theo IP). */
export const reportAbuse = (input: { shortCode: string; reason: AbuseReason; details?: string }) =>
  request<void>('/api/v1/public/reports', { method: 'POST', body: input });
