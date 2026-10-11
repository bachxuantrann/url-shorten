import { ApiError, NetworkError } from './client';
import type { MessageKey } from '../i18n/messages';

export interface ErrorView {
  /** Thông báo chung hiển thị phía trên form. */
  message: string;
  /** Lỗi theo từng field (chỉ có khi validation thất bại). */
  fields: Record<string, string>;
}

type T = (key: MessageKey, params?: Record<string, string | number>) => string;

/** Chuyển lỗi thành nội dung hiển thị. Message của server đã được dịch theo Accept-Language nên dùng nguyên. */
export function toErrorView(e: unknown, t: T): ErrorView {
  if (e instanceof NetworkError) return { message: t('error.network'), fields: {} };
  if (e instanceof ApiError) {
    if (e.status === 429) {
      return { message: t('error.rateLimited', { seconds: e.retryAfterSeconds ?? 60 }), fields: {} };
    }
    return { message: e.message || t('error.unknown'), fields: e.fields };
  }
  return { message: t('error.unknown'), fields: {} };
}
