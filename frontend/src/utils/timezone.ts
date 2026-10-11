/**
 * Quy đổi thời gian giữa múi giờ máy chủ và múi giờ của người dùng.
 *
 * API trả và nhận {@code LocalDateTime} dạng "yyyy-MM-ddTHH:mm:ss": giờ treo tường THEO MÚI GIỜ MÁY CHỦ, không kèm múi giờ.
 * Máy dev (giờ địa phương) và container prod (thường UTC) khác nhau nên cùng một chuỗi có thể là hai thời điểm khác nhau.
 * Mọi hàm ở đây nhận múi giờ TƯỜNG MINH (mã IANA) thay vì dựa vào múi giờ của máy chạy, nên kết quả xác định và test được.
 */

export const browserZone = (): string => Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC';

interface Parts {
  y: number;
  mo: number;
  d: number;
  h: number;
  mi: number;
  s: number;
}

const formatters = new Map<string, Intl.DateTimeFormat>();

function partsInZone(date: Date, zone: string): Parts {
  let f = formatters.get(zone);
  if (!f) {
    f = new Intl.DateTimeFormat('en-US', {
      timeZone: zone,
      hourCycle: 'h23',
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
    });
    formatters.set(zone, f);
  }
  const p: Record<string, string> = {};
  for (const part of f.formatToParts(date)) p[part.type] = part.value;
  return { y: +p.year, mo: +p.month, d: +p.day, h: +p.hour % 24, mi: +p.minute, s: +p.second };
}

/** Độ lệch (ms) của {@code zone} so với UTC tại thời điểm {@code date} (có tính giờ mùa hè). */
function offsetMs(date: Date, zone: string): number {
  const p = partsInZone(date, zone);
  return Date.UTC(p.y, p.mo - 1, p.d, p.h, p.mi, p.s) - Math.floor(date.getTime() / 1000) * 1000;
}

const WALL = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2}))?/;

const DAY_MS = 86_400_000;

/**
 * Giờ treo tường "yyyy-MM-ddTHH:mm[:ss]" trong {@code zone} -> thời điểm thật. Không hợp lệ (hoặc múi giờ lạ) -> Invalid Date.
 *
 * Thử hai độ lệch (một ngày trước và một ngày sau) rồi giữ ứng viên nào thật sự khớp độ lệch của nó:
 *  - bình thường: hai độ lệch như nhau, một kết quả;
 *  - giờ xuất hiện hai lần (lùi giờ mùa thu): cả hai khớp, lấy lần xuất hiện ĐẦU TIÊN;
 *  - giờ không tồn tại (nhảy giờ mùa xuân): không cái nào khớp, dùng độ lệch trước mốc đổi giờ nên giờ bị ĐẨY TIẾN
 *    (02:30 không có thì thành 03:30), cùng quy ước với Temporal/"compatible".
 */
export function wallToDate(wall: string, zone: string): Date {
  const m = WALL.exec(wall);
  if (!m) return new Date(NaN);
  const asUtc = Date.UTC(+m[1], +m[2] - 1, +m[3], +m[4], +m[5], m[6] ? +m[6] : 0);
  try {
    const before = offsetMs(new Date(asUtc - DAY_MS), zone);
    const after = offsetMs(new Date(asUtc + DAY_MS), zone);
    const c1 = asUtc - before;
    const c2 = asUtc - after;
    const ok1 = offsetMs(new Date(c1), zone) === before;
    const ok2 = offsetMs(new Date(c2), zone) === after;
    if (ok1 && ok2) return new Date(Math.min(c1, c2));
    if (ok2) return new Date(c2);
    return new Date(c1);
  } catch {
    return new Date(NaN);
  }
}

/** Múi giờ này trình duyệt có hiểu không (mã IANA hợp lệ). */
export function isValidZone(zone: string | null | undefined): zone is string {
  if (!zone) return false;
  try {
    new Intl.DateTimeFormat('en-US', { timeZone: zone });
    return true;
  } catch {
    return false;
  }
}

const pad = (n: number) => String(n).padStart(2, '0');

/** Thời điểm thật -> giờ treo tường trong {@code zone}, "yyyy-MM-ddTHH:mm" hoặc kèm giây. */
export function dateToWall(date: Date, zone: string, seconds = false): string {
  const p = partsInZone(date, zone);
  const base = `${p.y}-${pad(p.mo)}-${pad(p.d)}T${pad(p.h)}:${pad(p.mi)}`;
  return seconds ? `${base}:${pad(p.s)}` : base;
}

/** Hạn dùng của server (giờ máy chủ) -> giá trị cho <input type=datetime-local> (giờ của người dùng). Rỗng nếu không có. */
export function serverToInput(iso: string | null, serverZone: string, userZone: string): string {
  if (!iso) return '';
  const date = wallToDate(iso, serverZone);
  if (Number.isNaN(date.getTime())) return iso.slice(0, 16);
  try {
    return dateToWall(date, userZone);
  } catch {
    return iso.slice(0, 16);
  }
}

/** Giá trị của <input type=datetime-local> (giờ người dùng) -> chuỗi gửi lên server (giờ máy chủ). Rỗng -> undefined. */
export function inputToServer(value: string, serverZone: string, userZone: string): string | undefined {
  if (!value) return undefined;
  const date = wallToDate(value, userZone);
  if (Number.isNaN(date.getTime())) return `${value}:00`;
  try {
    return dateToWall(date, serverZone, true);
  } catch {
    return `${value}:00`; // múi giờ máy chủ lạ: gửi nguyên giá trị như trước khi có quy đổi
  }
}

const locale = (language: string) => (language === 'vi' ? 'vi-VN' : 'en-GB');

/** Thời điểm của server hiển thị theo múi giờ của người dùng. */
export function formatServerDateTime(iso: string, serverZone: string, userZone: string, language: string): string {
  const date = wallToDate(iso, serverZone);
  if (Number.isNaN(date.getTime())) return iso;
  try {
    return date.toLocaleString(locale(language), { timeZone: userZone, dateStyle: 'medium', timeStyle: 'short' });
  } catch {
    return iso;
  }
}

export function formatServerDate(iso: string, serverZone: string, userZone: string, language: string): string {
  const date = wallToDate(iso, serverZone);
  if (Number.isNaN(date.getTime())) return iso;
  try {
    return date.toLocaleDateString(locale(language), { timeZone: userZone, dateStyle: 'medium' });
  } catch {
    return iso;
  }
}

/** Nhãn múi giờ để hiện cho người dùng, ví dụ "GMT+7 · Asia/Saigon". */
export function zoneLabel(zone: string, language: string, at = new Date()): string {
  try {
    const parts = new Intl.DateTimeFormat(locale(language), { timeZone: zone, timeZoneName: 'shortOffset' }).formatToParts(at);
    const offset = parts.find((p) => p.type === 'timeZoneName')?.value;
    return offset ? `${offset} · ${zone}` : zone;
  } catch {
    return zone;
  }
}

/** Ngày yyyy-MM-dd của {@code now} trong {@code zone}. */
export function todayIn(zone: string, now = new Date()): string {
  const p = partsInZone(now, zone);
  return `${p.y}-${pad(p.mo)}-${pad(p.d)}`;
}

/** Khoảng {@code days} ngày kết thúc hôm nay (theo {@code zone}), cả hai đầu đều tính. */
export function lastDays(days: number, zone: string = browserZone(), now = new Date()): { from: string; to: string } {
  const p = partsInZone(now, zone);
  // Tính lùi ngày trên lịch (UTC chỉ làm nền số học, không có giờ mùa hè nên không lệch ngày)
  const start = new Date(Date.UTC(p.y, p.mo - 1, p.d - (days - 1)));
  return {
    from: `${start.getUTCFullYear()}-${pad(start.getUTCMonth() + 1)}-${pad(start.getUTCDate())}`,
    to: `${p.y}-${pad(p.mo)}-${pad(p.d)}`,
  };
}

/** Độ lệch so với UTC của {@code zone} tại {@code at}, tính bằng phút (UTC+7 -> 420). */
export function zoneOffsetMinutes(zone: string, at = new Date()): number {
  try {
    return Math.round(offsetMs(at, zone) / 60000);
  } catch {
    return 0;
  }
}
