import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { cloneElement, type ReactElement } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { UrlStats } from '../api/types';
import { callsTo, fail, link, me, ok, pageOf, renderApp, resetServer, serve } from '../test/helpers';
import { lastDays } from '../utils/timezone';

// jsdom không có layout nên ResponsiveContainer đo ra 0x0 và biểu đồ không vẽ: cho kích thước cố định
vi.mock('recharts', async (importOriginal) => {
  const actual = await importOriginal<typeof import('recharts')>();
  return {
    ...actual,
    ResponsiveContainer: ({ children, height }: { children: ReactElement<Record<string, unknown>>; height: number }) =>
      cloneElement(children, { width: 800, height }),
  };
});

beforeEach(() => resetServer());
afterEach(() => vi.unstubAllGlobals());

const stats = (extra: Partial<UrlStats> = {}): UrlStats => ({
  shortCode: 'abc1234',
  from: '2026-09-12',
  to: '2026-10-11',
  totals: { clicks: 120, botClicks: 20, uniqueVisitors: 70 },
  daily: [
    { date: '2026-10-10', clicks: 50, botClicks: 5, uniqueVisitors: 30 },
    { date: '2026-10-11', clicks: 70, botClicks: 15, uniqueVisitors: 40 },
  ],
  countries: [
    { name: 'VN', clicks: 80 },
    { name: 'Unknown', clicks: 20 },
  ],
  devices: [{ name: 'MOBILE', clicks: 60 }],
  browsers: [{ name: 'Chrome', clicks: 90 }],
  operatingSystems: [],
  ...extra,
});

const png = () => new Response(new Uint8Array([137, 80, 78, 71]), { status: 200, headers: { 'Content-Type': 'image/png' } });

function serveDetail(overrides: Parameters<typeof serve>[0] = {}) {
  serve({
    'GET /api/v1/users/me': me,
    'GET /api/v1/urls/abc1234': () => ok(link('abc1234', { clickCount: 120 })),
    'GET /api/v1/urls/abc1234/stats': () => ok(stats()),
    'GET /api/v1/urls/abc1234/qr': png,
    ...overrides,
  });
}

const statsCalls = () => callsTo('GET', '/api/v1/urls/abc1234/stats');
const kpi = (label: string) => screen.getByText(label, { selector: '.kpi .label' }).previousElementSibling as HTMLElement;

describe('chi tiết link', () => {
  it('hiện link ngắn, đích và trạng thái ở đầu trang, kèm đường dẫn về danh sách', async () => {
    serveDetail();
    renderApp('/links/abc1234', { authed: true });

    expect(await screen.findByRole('link', { name: 'sho.rt/abc1234' })).toHaveAttribute('href', 'https://sho.rt/abc1234');
    expect(screen.getAllByText('https://example.com/abc1234').length).toBeGreaterThan(0);
    expect(screen.getAllByText('Đang hoạt động').length).toBeGreaterThan(0);
    expect(screen.getByRole('navigation', { name: 'Breadcrumb' })).toHaveTextContent('Link của tôi');
    // Tiêu đề cập nhật trong một effect sau khi render: đợi thay vì kiểm ngay
    await waitFor(() => expect(document.title).toBe('sho.rt/abc1234 · Shortly'));
  });

  it('mặc định xem 30 ngày gần nhất và hiện bốn chỉ số tổng', async () => {
    serveDetail();
    renderApp('/links/abc1234', { authed: true });

    await screen.findByText('Tổng click');
    const expected = lastDays(30);
    await waitFor(() => expect(statsCalls()).toHaveLength(1));
    expect(statsCalls()[0].query.get('from')).toBe(expected.from);
    expect(statsCalls()[0].query.get('to')).toBe(expected.to);
    expect(kpi('Tổng click')).toHaveTextContent('120');
    expect(kpi('Click của người')).toHaveTextContent('100'); // 120 - 20 bot
    expect(kpi('Click của bot')).toHaveTextContent('20');
    expect(kpi('Khách duy nhất')).toHaveTextContent('70');
    expect(screen.getByText(/một người quay lại ở hai ngày được đếm hai lần/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '30 ngày qua' })).toHaveAttribute('aria-pressed', 'true');
  });

  it('có biểu đồ click theo thời gian và bốn nhóm phân bổ', async () => {
    serveDetail();
    renderApp('/links/abc1234', { authed: true });

    expect(await screen.findByRole('img', { name: 'Lượt click theo thời gian' })).toBeInTheDocument();
    for (const title of ['Quốc gia', 'Thiết bị', 'Trình duyệt', 'Hệ điều hành']) {
      expect(screen.getByRole('heading', { name: title })).toBeInTheDocument();
    }
  });

  it('phân bổ dịch tên theo ngôn ngữ, hiện tỉ lệ phần trăm và thanh tỉ lệ', async () => {
    serveDetail();
    renderApp('/links/abc1234', { authed: true });

    const countries = (await screen.findByRole('heading', { name: 'Quốc gia' })).closest('section') as HTMLElement;
    expect(within(countries).getByText('Việt Nam')).toBeInTheDocument(); // VN dịch theo ngôn ngữ giao diện
    expect(within(countries).getByText('Không rõ')).toBeInTheDocument(); // "Unknown" của server
    expect(within(countries).getByText(/80%/)).toBeInTheDocument();
    expect(within(countries).getByText(/20%/)).toBeInTheDocument();
    expect(countries.querySelector<HTMLElement>('.bar > span')!.style.width).toBe('80%');
    const devices = screen.getByRole('heading', { name: 'Thiết bị' }).closest('section') as HTMLElement;
    expect(within(devices).getByText('Điện thoại')).toBeInTheDocument();
    const os = screen.getByRole('heading', { name: 'Hệ điều hành' }).closest('section') as HTMLElement;
    expect(within(os).getByText('Chưa có dữ liệu')).toBeInTheDocument();
  });

  it('bấm 7 ngày qua gọi lại với đúng khoảng và đánh dấu nút đang chọn', async () => {
    serveDetail();
    renderApp('/links/abc1234', { authed: true });
    await screen.findByText('Tổng click');

    await userEvent.click(screen.getByRole('button', { name: '7 ngày qua' }));

    await waitFor(() => expect(statsCalls()).toHaveLength(2));
    const expected = lastDays(7);
    expect(statsCalls()[1].query.get('from')).toBe(expected.from);
    expect(statsCalls()[1].query.get('to')).toBe(expected.to);
    expect(screen.getByLabelText('Từ ngày')).toHaveValue(expected.from);
    expect(screen.getByRole('button', { name: '7 ngày qua' })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('button', { name: '30 ngày qua' })).toHaveAttribute('aria-pressed', 'false');
  });

  it('chọn ngày tuỳ ý gửi đúng from/to và bỏ đánh dấu các nút có sẵn', async () => {
    serveDetail();
    renderApp('/links/abc1234', { authed: true });
    await screen.findByText('Tổng click');

    const from = screen.getByLabelText('Từ ngày');
    await userEvent.clear(from);
    await userEvent.type(from, '2026-01-05');

    await waitFor(() => expect(statsCalls().at(-1)!.query.get('from')).toBe('2026-01-05'));
    for (const name of ['7 ngày qua', '30 ngày qua', '90 ngày qua']) {
      expect(screen.getByRole('button', { name })).toHaveAttribute('aria-pressed', 'false');
    }
  });

  it('chưa có click nào: báo chưa có dữ liệu thay vì vẽ biểu đồ trống', async () => {
    serveDetail({
      'GET /api/v1/urls/abc1234/stats': () =>
        ok(stats({ totals: { clicks: 0, botClicks: 0, uniqueVisitors: 0 }, daily: [], countries: [], devices: [], browsers: [] })),
    });
    renderApp('/links/abc1234', { authed: true });

    expect(await screen.findByText(/Chưa có lượt click nào trong khoảng này/)).toBeInTheDocument();
    expect(screen.queryByRole('img', { name: 'Lượt click theo thời gian' })).not.toBeInTheDocument();
    expect(kpi('Tổng click')).toHaveTextContent('0');
  });

  it('khoảng ngày không hợp lệ (ERR_411) hiện thông báo của server', async () => {
    serveDetail({ 'GET /api/v1/urls/abc1234/stats': () => fail(400, 'ERR_411', 'Khoảng thời gian tối đa 366 ngày') });
    renderApp('/links/abc1234', { authed: true });

    expect(await screen.findByRole('alert')).toHaveTextContent('Khoảng thời gian tối đa 366 ngày');
  });

  it('link của người khác hoặc không tồn tại (404) hiện trạng thái không tìm thấy với nút quay lại', async () => {
    serveDetail({
      'GET /api/v1/urls/abc1234': () => fail(404, 'ERR_400', 'x'),
      'GET /api/v1/urls/abc1234/stats': () => fail(404, 'ERR_400', 'x'),
    });
    renderApp('/links/abc1234', { authed: true });

    expect(await screen.findByText('Không tìm thấy link')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Quay lại' })).toHaveAttribute('href', '/dashboard');
  });

  it('khối chi tiết liệt kê đích, ngày tạo, hạn dùng và trạng thái mật khẩu', async () => {
    serveDetail({
      'GET /api/v1/urls/abc1234': () => ok(link('abc1234', { passwordProtected: true, expiresAt: '2030-12-31T23:59:00' })),
    });
    renderApp('/links/abc1234', { authed: true });

    const details = (await screen.findByRole('heading', { name: 'Chi tiết' })).closest('section') as HTMLElement;
    expect(within(details).getByText('Đang bảo vệ')).toBeInTheDocument();
    expect(within(details).getByText(/2030/)).toBeInTheDocument();
    expect(within(details).getByText('Đích').nextElementSibling).toHaveTextContent('https://example.com/abc1234');
  });

  it('link bị chặn hiện lý do ở đầu trang và không cho sửa/xoá', async () => {
    serveDetail({
      'GET /api/v1/urls/abc1234': () => ok(link('abc1234', { status: 'BLOCKED', blockedReason: 'phishing' })),
    });
    renderApp('/links/abc1234', { authed: true });

    expect(await screen.findByText(/Quản trị viên đã chặn: phishing/)).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Thêm thao tác' }));
    expect(screen.queryByRole('menuitem', { name: 'Xoá' })).not.toBeInTheDocument();
    expect(screen.queryByRole('menuitem', { name: 'Sửa đích' })).not.toBeInTheDocument();
  });

  it('chưa đăng nhập thì bị chuyển sang trang đăng nhập', async () => {
    renderApp('/links/abc1234');

    expect(await screen.findByRole('heading', { name: 'Chào mừng trở lại' })).toBeInTheDocument();
  });
});

describe('thao tác ngay trên trang chi tiết', () => {
  it('sửa đích cập nhật phần đầu trang ngay lập tức', async () => {
    serveDetail({ 'PUT /api/v1/urls/abc1234': () => ok(link('abc1234', { originalUrl: 'https://new.example.com/page' })) });
    renderApp('/links/abc1234', { authed: true });
    await screen.findByRole('link', { name: 'sho.rt/abc1234' });

    await userEvent.click(screen.getByRole('button', { name: 'Thêm thao tác' }));
    await userEvent.click(screen.getByRole('menuitem', { name: 'Sửa đích' }));
    const dialog = within(screen.getByRole('dialog'));
    await userEvent.clear(dialog.getByLabelText('URL đích'));
    await userEvent.type(dialog.getByLabelText('URL đích'), 'https://new.example.com/page');
    await userEvent.click(dialog.getByRole('button', { name: 'Lưu thay đổi' }));

    await waitFor(() => expect(screen.getAllByText('https://new.example.com/page').length).toBeGreaterThan(0));
  });

  it('tắt link đổi huy hiệu trạng thái', async () => {
    serveDetail({ 'PUT /api/v1/urls/abc1234/disable': () => ok(link('abc1234', { status: 'DISABLED' })) });
    renderApp('/links/abc1234', { authed: true });
    await screen.findByRole('link', { name: 'sho.rt/abc1234' });

    await userEvent.click(screen.getByRole('button', { name: 'Thêm thao tác' }));
    await userEvent.click(screen.getByRole('menuitem', { name: 'Tắt' }));

    expect((await screen.findAllByText('Đã tắt')).length).toBeGreaterThan(0);
  });

  it('nút Mã QR mở hộp thoại và tải ảnh có xác thực', async () => {
    URL.createObjectURL = vi.fn(() => 'blob:qr-1');
    URL.revokeObjectURL = vi.fn();
    serveDetail();
    renderApp('/links/abc1234', { authed: true });
    await screen.findByRole('link', { name: 'sho.rt/abc1234' });

    await userEvent.click(screen.getByRole('button', { name: 'Mã QR' }));

    expect(await within(screen.getByRole('dialog')).findByAltText('Mã QR của https://sho.rt/abc1234')).toHaveAttribute('src', 'blob:qr-1');
    expect(callsTo('GET', '/api/v1/urls/abc1234/qr')[0].headers.Authorization).toBe('Bearer at');
  });

  it('xoá link quay về danh sách', async () => {
    serveDetail({
      'DELETE /api/v1/urls/abc1234': () => ok(null),
      'GET /api/v1/urls': () => ok(pageOf([])),
    });
    renderApp('/links/abc1234', { authed: true });
    await screen.findByRole('link', { name: 'sho.rt/abc1234' });

    await userEvent.click(screen.getByRole('button', { name: 'Thêm thao tác' }));
    await userEvent.click(screen.getByRole('menuitem', { name: 'Xoá' }));
    await userEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Xoá link' }));

    expect(await screen.findByRole('heading', { name: 'Link của tôi', level: 1 })).toBeInTheDocument();
    expect(await screen.findByText('Tạo link rút gọn đầu tiên của bạn')).toBeInTheDocument();
  });
});
