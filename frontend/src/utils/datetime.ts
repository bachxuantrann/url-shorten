/** Backend dùng LocalDateTime "yyyy-MM-ddTHH:mm:ss" (không múi giờ); <input type=datetime-local> dùng "yyyy-MM-ddTHH:mm". */
export const toInputValue = (iso: string | null): string => (iso ? iso.slice(0, 16) : '');

export const fromInputValue = (value: string): string | undefined => (value ? `${value}:00` : undefined);

export function formatDateTime(iso: string, language: string): string {
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleString(language === 'vi' ? 'vi-VN' : 'en-GB', { dateStyle: 'medium', timeStyle: 'short' });
}

/** yyyy-MM-dd theo giờ địa phương (không dùng toISOString vì nó đổi sang UTC và có thể lệch một ngày). */
export function toDateInput(d: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

/** Khoảng {@code days} ngày kết thúc hôm nay (cả hai đầu đều tính). */
export function lastDays(days: number, now = new Date()): { from: string; to: string } {
  const start = new Date(now.getFullYear(), now.getMonth(), now.getDate() - (days - 1));
  return { from: toDateInput(start), to: toDateInput(now) };
}

/** Chỉ ngày (không giờ), theo ngôn ngữ giao diện. */
export function formatDate(iso: string, language: string): string {
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleDateString(language === 'vi' ? 'vi-VN' : 'en-GB', { dateStyle: 'medium' });
}

/** Bỏ giao thức và dấu / cuối để hiển thị link ngắn gọn (https://sho.rt/abc/ -> sho.rt/abc). */
export function displayUrl(url: string): string {
  return url.replace(/^https?:\/\//, '').replace(/\/$/, '');
}
