import { describe, expect, it } from 'vitest';
import {
  dateToWall,
  formatServerDate,
  formatServerDateTime,
  inputToServer,
  isValidZone,
  lastDays,
  serverToInput,
  todayIn,
  wallToDate,
  zoneLabel,
  zoneOffsetMinutes,
} from './timezone';

const HCM = 'Asia/Ho_Chi_Minh'; // UTC+7, không đổi giờ mùa hè
const NY = 'America/New_York'; // UTC-5 mùa đông, UTC-4 mùa hè
const KOLKATA = 'Asia/Kolkata'; // UTC+5:30 (độ lệch không tròn giờ)

const iso = (d: Date) => d.toISOString();

describe('wallToDate: giờ treo tường trong một múi giờ -> thời điểm thật', () => {
  it('múi giờ cố định', () => {
    expect(iso(wallToDate('2026-10-11T12:00:00', 'UTC'))).toBe('2026-10-11T12:00:00.000Z');
    expect(iso(wallToDate('2026-10-11T12:00:00', HCM))).toBe('2026-10-11T05:00:00.000Z');
    expect(iso(wallToDate('2026-10-11T12:00:00', KOLKATA))).toBe('2026-10-11T06:30:00.000Z');
  });

  it('chấp nhận cả dạng không có giây (giá trị của ô datetime-local)', () => {
    expect(iso(wallToDate('2026-10-11T12:00', HCM))).toBe('2026-10-11T05:00:00.000Z');
  });

  it('có giờ mùa hè: cùng 12:00 nhưng độ lệch khác nhau theo mùa', () => {
    expect(iso(wallToDate('2026-07-01T12:00:00', NY))).toBe('2026-07-01T16:00:00.000Z'); // EDT
    expect(iso(wallToDate('2026-01-15T12:00:00', NY))).toBe('2026-01-15T17:00:00.000Z'); // EST
  });

  it('giờ không tồn tại (nhảy giờ mùa xuân) được đẩy sang giờ hợp lệ gần nhất, không ra Invalid Date', () => {
    const date = wallToDate('2026-03-08T02:30:00', NY); // 02:00 -> 03:00 hôm đó nên 02:30 không có

    expect(Number.isNaN(date.getTime())).toBe(false);
    expect(dateToWall(date, NY)).toBe('2026-03-08T03:30');
  });

  it('giờ xuất hiện hai lần (lùi giờ mùa thu) vẫn quay lại đúng giờ treo tường đó', () => {
    const date = wallToDate('2026-11-01T01:30:00', NY);

    expect(Number.isNaN(date.getTime())).toBe(false);
    expect(dateToWall(date, NY)).toBe('2026-11-01T01:30');
  });

  it('đầu vào hỏng hoặc múi giờ lạ cho Invalid Date thay vì ném lỗi', () => {
    expect(Number.isNaN(wallToDate('khong-phai-ngay', HCM).getTime())).toBe(true);
    expect(Number.isNaN(wallToDate('2026-10-11T12:00:00', 'Khong/Co').getTime())).toBe(true);
    expect(Number.isNaN(wallToDate('', 'UTC').getTime())).toBe(true);
  });
});

describe('dateToWall và vòng đi-về', () => {
  it('định dạng giờ treo tường theo múi giờ, có hoặc không có giây', () => {
    const date = new Date('2026-10-11T05:07:09Z');

    expect(dateToWall(date, HCM)).toBe('2026-10-11T12:07');
    expect(dateToWall(date, HCM, true)).toBe('2026-10-11T12:07:09');
    expect(dateToWall(date, 'UTC', true)).toBe('2026-10-11T05:07:09');
  });

  it('nửa đêm hiển thị 00 chứ không phải 24', () => {
    expect(dateToWall(new Date('2026-10-11T17:00:00Z'), HCM)).toBe('2026-10-12T00:00');
  });

  it('wall -> date -> wall trả đúng giá trị ban đầu, ở nhiều múi giờ và mùa', () => {
    const zones = ['UTC', HCM, NY, KOLKATA, 'Europe/London', 'Australia/Sydney', 'Pacific/Auckland'];
    const walls = ['2026-01-01T00:00:00', '2026-06-30T23:59:59', '2026-12-31T12:34:56', '2030-02-28T08:15:00', '2028-02-29T23:00:00'];
    for (const zone of zones) {
      for (const wall of walls) {
        expect(dateToWall(wallToDate(wall, zone), zone, true), `${zone} ${wall}`).toBe(wall);
      }
    }
  });
});

describe('quy đổi giữa máy chủ và người dùng', () => {
  it('server UTC, người dùng UTC+7: hạn dùng hiện muộn hơn 7 tiếng so với giờ máy chủ', () => {
    expect(serverToInput('2030-01-02T03:04:00', 'UTC', HCM)).toBe('2030-01-02T10:04');
  });

  it('gửi lên: giờ người dùng nhập được đổi sang giờ máy chủ, kể cả khi lệch sang ngày khác', () => {
    expect(inputToServer('2030-01-02T10:04', 'UTC', HCM)).toBe('2030-01-02T03:04:00');
    expect(inputToServer('2030-01-02T02:00', 'UTC', HCM)).toBe('2030-01-01T19:00:00'); // lùi sang ngày hôm trước
    expect(inputToServer('2030-01-01T22:00', HCM, 'UTC')).toBe('2030-01-02T05:00:00'); // tiến sang ngày hôm sau
  });

  it('hai chiều là nghịch đảo của nhau: mở form sửa rồi lưu không làm đổi hạn dùng', () => {
    for (const [server, user] of [['UTC', HCM], [HCM, 'UTC'], [NY, KOLKATA], ['Europe/London', NY]] as const) {
      for (const stored of ['2026-03-29T00:30:00', '2026-07-01T12:00:00', '2026-11-01T23:59:00', '2030-12-31T23:59:00']) {
        const shown = serverToInput(stored, server, user);
        expect(inputToServer(shown, server, user), `${server}->${user} ${stored}`).toBe(stored);
      }
    }
  });

  it('cùng múi giờ thì không đổi gì (chỉ thêm :00 giây)', () => {
    expect(serverToInput('2030-01-02T03:04:00', HCM, HCM)).toBe('2030-01-02T03:04');
    expect(inputToServer('2030-01-02T03:04', HCM, HCM)).toBe('2030-01-02T03:04:00');
  });

  it('không có hạn dùng: ô rỗng và không gửi gì', () => {
    expect(serverToInput(null, 'UTC', HCM)).toBe('');
    expect(inputToServer('', 'UTC', HCM)).toBeUndefined();
  });

  it('múi giờ máy chủ lạ thì rơi về hành vi cũ thay vì làm vỡ form', () => {
    expect(serverToInput('2030-01-02T03:04:00', 'Khong/Co', HCM)).toBe('2030-01-02T03:04');
    expect(inputToServer('2030-01-02T03:04', 'Khong/Co', HCM)).toBe('2030-01-02T03:04:00');
  });
});

describe('hiển thị', () => {
  it('thời điểm của server hiện theo giờ người dùng, qua cả nửa đêm', () => {
    const text = formatServerDateTime('2026-10-11T23:30:00', 'UTC', HCM, 'en');

    expect(text).toContain('12 Oct 2026');
    expect(text).toContain('06:30');
    expect(formatServerDate('2026-10-11T23:30:00', 'UTC', HCM, 'en')).toBe('12 Oct 2026');
    expect(formatServerDate('2026-10-11T23:30:00', 'UTC', 'UTC', 'en')).toBe('11 Oct 2026');
  });

  it('theo ngôn ngữ giao diện', () => {
    const vi = formatServerDate('2026-10-11T23:30:00', 'UTC', HCM, 'vi');

    expect(vi).toContain('2026');
    expect(vi).not.toBe(formatServerDate('2026-10-11T23:30:00', 'UTC', HCM, 'en'));
  });

  it('giá trị không đọc được được trả về nguyên văn', () => {
    expect(formatServerDateTime('hu-hong', 'UTC', HCM, 'en')).toBe('hu-hong');
    expect(formatServerDate('hu-hong', 'UTC', HCM, 'en')).toBe('hu-hong');
  });

  it('nhãn múi giờ gồm độ lệch và mã vùng', () => {
    expect(zoneLabel(HCM, 'en')).toContain('GMT+7');
    expect(zoneLabel(HCM, 'en')).toContain(HCM);
    expect(zoneLabel('Khong/Co', 'en')).toBe('Khong/Co');
  });

  it('độ lệch tính bằng phút, có giờ mùa hè và độ lệch lẻ', () => {
    expect(zoneOffsetMinutes(HCM)).toBe(420);
    expect(zoneOffsetMinutes(KOLKATA)).toBe(330);
    expect(zoneOffsetMinutes(NY, new Date('2026-07-01T12:00:00Z'))).toBe(-240);
    expect(zoneOffsetMinutes(NY, new Date('2026-01-15T12:00:00Z'))).toBe(-300);
    expect(zoneOffsetMinutes('UTC')).toBe(0);
  });
});

describe('ngày "hôm nay" và khoảng ngày theo múi giờ', () => {
  const now = new Date('2026-10-11T20:00:00Z'); // đã sang ngày 12 ở UTC+7 nhưng vẫn là ngày 11 ở UTC

  it('todayIn theo múi giờ', () => {
    expect(todayIn(HCM, now)).toBe('2026-10-12');
    expect(todayIn('UTC', now)).toBe('2026-10-11');
    expect(todayIn(NY, now)).toBe('2026-10-11');
    expect(todayIn('Pacific/Kiritimati', now)).toBe('2026-10-12'); // UTC+14
  });

  it('khoảng ngày kết thúc ở "hôm nay" của múi giờ đó, cả hai đầu đều tính', () => {
    expect(lastDays(7, 'UTC', now)).toEqual({ from: '2026-10-05', to: '2026-10-11' });
    expect(lastDays(7, HCM, now)).toEqual({ from: '2026-10-06', to: '2026-10-12' });
    expect(lastDays(1, HCM, now)).toEqual({ from: '2026-10-12', to: '2026-10-12' });
  });

  it('lùi qua ranh giới tháng, năm và năm nhuận', () => {
    expect(lastDays(5, 'UTC', new Date('2026-03-02T12:00:00Z'))).toEqual({ from: '2026-02-26', to: '2026-03-02' });
    expect(lastDays(3, 'UTC', new Date('2028-03-01T12:00:00Z'))).toEqual({ from: '2028-02-28', to: '2028-03-01' }); // 2028 nhuận
    expect(lastDays(3, 'UTC', new Date('2027-01-01T12:00:00Z'))).toEqual({ from: '2026-12-30', to: '2027-01-01' });
    expect(lastDays(30, 'UTC', new Date('2026-10-11T12:00:00Z'))).toEqual({ from: '2026-09-12', to: '2026-10-11' });
  });

  it('khoảng ngày không bị lệch quanh ngày đổi giờ mùa hè', () => {
    expect(lastDays(3, NY, new Date('2026-03-09T12:00:00Z'))).toEqual({ from: '2026-03-07', to: '2026-03-09' });
    expect(lastDays(3, NY, new Date('2026-11-02T12:00:00Z'))).toEqual({ from: '2026-10-31', to: '2026-11-02' });
  });
});

describe('isValidZone', () => {
  it('nhận mã IANA hợp lệ, từ chối giá trị lạ hoặc rỗng', () => {
    expect(isValidZone('UTC')).toBe(true);
    expect(isValidZone(HCM)).toBe(true);
    expect(isValidZone('Khong/Co')).toBe(false);
    expect(isValidZone('')).toBe(false);
    expect(isValidZone(null)).toBe(false);
    expect(isValidZone(undefined)).toBe(false);
  });
});
