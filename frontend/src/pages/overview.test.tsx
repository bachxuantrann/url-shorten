import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { cloneElement, type ReactElement } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { StatsOverview } from '../api/types';
import { callsTo, created, fail, link, me, mockMatchMedia, ok, renderApp, resetServer, serve } from '../test/helpers';
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

beforeEach(() => {
  resetServer();
  mockMatchMedia(() => false);
});
afterEach(() => vi.unstubAllGlobals());

const overview = (extra: Partial<StatsOverview> = {}): StatsOverview => ({
  from: '2026-09-12',
  to: '2026-10-11',
  links: { total: 12, active: 8, disabled: 2, expired: 1, blocked: 1 },
  totals: { clicks: 340, botClicks: 40, uniqueVisitors: 200 },
  daily: [
    { date: '2026-10-10', clicks: 140, botClicks: 10, uniqueVisitors: 80 },
    { date: '2026-10-11', clicks: 200, botClicks: 30, uniqueVisitors: 120 },
  ],
  topLinks: [
    { shortCode: 'abc1234', shortUrl: 'https://sho.rt/abc1234', originalUrl: 'https://example.com/abc1234', clicks: 250 },
    { shortCode: 'def5678', shortUrl: 'https://sho.rt/def5678', originalUrl: 'https://example.com/def5678', clicks: 90 },
  ],
  countries: [
    { name: 'VN', clicks: 200 },
    { name: 'Unknown', clicks: 100 },
  ],
  devices: [{ name: 'MOBILE', clicks: 180 }],
  browsers: [{ name: 'Chrome', clicks: 260 }],
  operatingSystems: [{ name: 'Windows', clicks: 150 }],
  ...extra,
});

const OVERVIEW = 'GET /api/v1/stats/overview';
const overviewCalls = () => callsTo('GET', '/api/v1/stats/overview');
const kpi = (label: string) => screen.getByText(label, { selector: '.kpi .label' }).previousElementSibling as HTMLElement;
const chip = (label: string) => screen.getByText(label, { selector: '.count-chip dt' }).nextElementSibling as HTMLElement;

describe('tổng quan tài khoản', () => {
  it('mặc định xem 30 ngày gần nhất và hiện bốn chỉ số tổng', async () => {
    serve({ 'GET /api/v1/users/me': me, [OVERVIEW]: () => ok(overview()) });
    renderApp('/overview', { authed: true });

    await screen.findByText('Tổng click');
    const expected = lastDays(30);
    await waitFor(() => expect(overviewCalls()).toHaveLength(1));
    expect(overviewCalls()[0].query.get('from')).toBe(expected.from);
    expect(overviewCalls()[0].query.get('to')).toBe(expected.to);
    expect(kpi('Tổng click')).toHaveTextContent('340');
    expect(kpi('Click của người')).toHaveTextContent('300'); // 340 - 40 bot
    expect(kpi('Click của bot')).toHaveTextContent('40');
    expect(kpi('Khách duy nhất')).toHaveTextContent('200');
  });

  it('hiện số link theo trạng thái và lối sang trang quản lý link', async () => {
    serve({ 'GET /api/v1/users/me': me, [OVERVIEW]: () => ok(overview()) });
    renderApp('/overview', { authed: true });

    await screen.findByText('Link của bạn');
    expect(chip('Tổng số')).toHaveTextContent('12');
    expect(chip('Đang hoạt động')).toHaveTextContent('8');
    expect(chip('Đã tắt')).toHaveTextContent('2');
    expect(chip('Hết hạn')).toHaveTextContent('1');
    expect(chip('Bị chặn')).toHaveTextContent('1');
    expect(screen.getByRole('link', { name: 'Quản lý link' })).toHaveAttribute('href', '/dashboard');
  });

  it('xếp hạng link nhiều click nhất: đúng thứ tự, kèm đích, mỗi link dẫn tới thống kê riêng', async () => {
    serve({ 'GET /api/v1/users/me': me, [OVERVIEW]: () => ok(overview()) });
    renderApp('/overview', { authed: true });

    const list = within(await screen.findByRole('region', { name: 'Link nhiều click nhất' }));
    const items = list.getAllByRole('listitem');
    expect(items).toHaveLength(2);
    expect(within(items[0]).getByRole('link', { name: 'sho.rt/abc1234' })).toHaveAttribute('href', '/links/abc1234');
    expect(items[0]).toHaveTextContent('250 lượt click');
    expect(items[0]).toHaveTextContent('https://example.com/abc1234');
    expect(within(items[1]).getByRole('link', { name: 'sho.rt/def5678' })).toHaveAttribute('href', '/links/def5678');
  });

  it('hiện các bảng phân bổ giống trang thống kê một link', async () => {
    serve({ 'GET /api/v1/users/me': me, [OVERVIEW]: () => ok(overview()) });
    renderApp('/overview', { authed: true });

    await screen.findByText('Tổng click');
    expect(screen.getByRole('heading', { name: 'Quốc gia' })).toBeInTheDocument();
    expect(screen.getByText('Chrome')).toBeInTheDocument();
    expect(screen.getByText('Windows')).toBeInTheDocument();
    expect(screen.getByRole('img', { name: 'Lượt click theo thời gian' })).toBeInTheDocument();
  });

  it('đổi khoảng ngày bằng nút 7 ngày hoặc ô ngày thì gọi lại với khoảng mới', async () => {
    serve({ 'GET /api/v1/users/me': me, [OVERVIEW]: () => ok(overview()) });
    renderApp('/overview', { authed: true });
    await screen.findByText('Tổng click');

    await userEvent.click(screen.getByRole('button', { name: '7 ngày qua' }));
    await waitFor(() => expect(overviewCalls().at(-1)!.query.get('from')).toBe(lastDays(7).from));
    expect(overviewCalls().at(-1)!.query.get('to')).toBe(lastDays(7).to);

    fireEvent.change(screen.getByLabelText('Từ ngày'), { target: { value: '2026-10-01' } });
    await waitFor(() => expect(overviewCalls().at(-1)!.query.get('from')).toBe('2026-10-01'));
  });

  it('khoảng không có click: báo rõ và bảng xếp hạng nói chưa có lượt click', async () => {
    serve({
      'GET /api/v1/users/me': me,
      [OVERVIEW]: () => ok(overview({ totals: { clicks: 0, botClicks: 0, uniqueVisitors: 0 }, topLinks: [], countries: [], devices: [] })),
    });
    renderApp('/overview', { authed: true });

    expect(await screen.findByText(/Chưa có lượt click nào trong khoảng này\. Hãy chia sẻ/)).toBeInTheDocument();
    const top = within(screen.getByRole('region', { name: 'Link nhiều click nhất' }));
    expect(top.getByText('Chưa có lượt click nào trong khoảng này.')).toBeInTheDocument();
    expect(top.queryByRole('listitem')).not.toBeInTheDocument();
    expect(kpi('Tổng click')).toHaveTextContent('0');
  });

  it('chưa có link nào: hiện lời mời tạo link thay cho các biểu đồ rỗng, bấm là mở hộp thoại tạo', async () => {
    serve({
      'GET /api/v1/users/me': me,
      [OVERVIEW]: () => ok(overview({ links: { total: 0, active: 0, disabled: 0, expired: 0, blocked: 0 }, totals: { clicks: 0, botClicks: 0, uniqueVisitors: 0 }, topLinks: [], countries: [], devices: [], browsers: [], operatingSystems: [] })),
    });
    renderApp('/overview', { authed: true });

    expect(await screen.findByText('Tạo link rút gọn đầu tiên của bạn')).toBeInTheDocument();
    expect(screen.queryByText('Tổng click')).not.toBeInTheDocument();
    expect(screen.queryByLabelText('Từ ngày')).not.toBeInTheDocument();

    const main = within(screen.getByRole('main'));
    await userEvent.click(main.getByRole('button', { name: 'Tạo link' }));
    expect(screen.getByRole('dialog', { name: 'Tạo link rút gọn' })).toBeInTheDocument();
  });

  it('tạo link ở hộp thoại toàn cục thì tổng quan tự tải lại (số link tăng)', async () => {
    let total = 0;
    serve({
      'GET /api/v1/users/me': me,
      [OVERVIEW]: () => ok(total === 0
        ? overview({ links: { total: 0, active: 0, disabled: 0, expired: 0, blocked: 0 }, totals: { clicks: 0, botClicks: 0, uniqueVisitors: 0 }, topLinks: [], countries: [], devices: [], browsers: [], operatingSystems: [] })
        : overview()),
      'POST /api/v1/urls': () => {
        total = 1;
        return created(link('new1234'));
      },
    });
    renderApp('/overview', { authed: true });
    await screen.findByText('Tạo link rút gọn đầu tiên của bạn');

    await userEvent.click(within(screen.getByRole('banner')).getByRole('button', { name: 'Tạo link' }));
    await userEvent.type(screen.getByLabelText('URL đích'), 'https://example.com/new');
    await userEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Tạo link' }));

    expect(await screen.findByText('Link của bạn')).toBeInTheDocument();
    expect(chip('Tổng số')).toHaveTextContent('12');
  });

  it('lỗi tải: báo lỗi kèm nút thử lại, bấm thì tải lại được', async () => {
    let attempts = 0;
    serve({
      'GET /api/v1/users/me': me,
      [OVERVIEW]: () => (++attempts === 1 ? fail(500, 'ERR_999', 'Lỗi máy chủ') : ok(overview())),
    });
    renderApp('/overview', { authed: true });

    expect(await screen.findByRole('alert')).toHaveTextContent('Lỗi máy chủ');
    await userEvent.click(screen.getByRole('button', { name: 'Thử lại' }));
    expect(await screen.findByText('Tổng click')).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('khoảng ngày bị từ chối (ERR_411): hiện lỗi của server, không có nút thử lại, số liệu cũ bị làm mờ', async () => {
    serve({
      'GET /api/v1/users/me': me,
      [OVERVIEW]: (c) => (c.query.get('from') === '2020-01-01' ? fail(400, 'ERR_411', 'Khoảng tối đa 366 ngày') : ok(overview())),
    });
    renderApp('/overview', { authed: true });
    await screen.findByText('Tổng click');

    fireEvent.change(screen.getByLabelText('Từ ngày'), { target: { value: '2020-01-01' } });

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Khoảng tối đa 366 ngày');
    // Lỗi do chính khoảng ngày: thử lại y nguyên vẫn lỗi nên không có nút; số liệu cũ vẫn còn nhưng bị làm mờ
    expect(within(alert).queryByRole('button', { name: 'Thử lại' })).not.toBeInTheDocument();
    expect(kpi('Tổng click')).toHaveTextContent('340');
    expect(kpi('Tổng click').closest('.stale')).not.toBeNull();

    // Sửa lại khoảng ngày hợp lệ thì lỗi và lớp mờ biến mất
    await userEvent.click(screen.getByRole('button', { name: '7 ngày qua' }));
    await waitFor(() => expect(screen.queryByRole('alert')).not.toBeInTheDocument());
    expect(kpi('Tổng click').closest('.stale')).toBeNull();
  });

  it('mục "Tổng quan" nằm đầu thanh bên và được đánh dấu khi đang ở trang', async () => {
    serve({ 'GET /api/v1/users/me': me, [OVERVIEW]: () => ok(overview()) });
    renderApp('/overview', { authed: true });
    await screen.findByText('Tổng click');

    const nav = within(screen.getByRole('navigation', { name: 'Main' }));
    const items = nav.getAllByRole('link');
    expect(items[0]).toHaveTextContent('Tổng quan');
    expect(items[0]).toHaveAttribute('href', '/overview');
    expect(items[0]).toHaveClass('active');
    expect(items[1]).toHaveTextContent('Link của tôi');
    expect(items[1]).not.toHaveClass('active');
  });

  it('chưa đăng nhập thì bị chuyển sang trang đăng nhập', async () => {
    serve({});
    renderApp('/overview');

    expect(await screen.findByRole('button', { name: /Đăng nhập/ })).toBeInTheDocument();
    expect(overviewCalls()).toHaveLength(0);
  });
});
