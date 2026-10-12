import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { cloneElement, type ReactElement } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { AdminUrl, UrlStats } from '../api/types';
import { callsTo, fail, link, me, mockMatchMedia, ok, pageOf, renderApp, resetServer, serve, USER } from '../test/helpers';
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

const ADMIN = { ...USER, id: 'admin-1', username: 'root', fullName: 'Root Admin', roles: ['ROLE_USER', 'ROLE_ADMIN'] };
const adminMe = () => ok(ADMIN);
const dialog = () => within(screen.getByRole('dialog'));

const adminLink = (code: string, extra: Partial<AdminUrl> = {}): AdminUrl => ({
  ...link(code),
  userId: 'u-1',
  createdBy: 'alice',
  updatedBy: 'alice',
  ...extra,
});

const stats = (extra: Partial<UrlStats> = {}): UrlStats => ({
  shortCode: 'abc1234',
  from: '2026-09-12',
  to: '2026-10-11',
  totals: { clicks: 120, botClicks: 20, uniqueVisitors: 70 },
  daily: [{ date: '2026-10-11', clicks: 120, botClicks: 20, uniqueVisitors: 70 }],
  countries: [{ name: 'VN', clicks: 80 }],
  devices: [{ name: 'MOBILE', clicks: 60 }],
  browsers: [{ name: 'Chrome', clicks: 90 }],
  operatingSystems: [],
  ...extra,
});

const kpi = (label: string) => screen.getByText(label, { selector: '.kpi .label' }).previousElementSibling as HTMLElement;

function serveDetail(overrides: Parameters<typeof serve>[0] = {}) {
  serve({
    'GET /api/v1/users/me': adminMe,
    'GET /api/v1/admin/urls/abc1234': () => ok(adminLink('abc1234', { clickCount: 120 })),
    'GET /api/v1/admin/urls/abc1234/stats': () => ok(stats()),
    ...overrides,
  });
}

describe('quản trị: chi tiết và thống kê của một link', () => {
  it('tên link trong danh sách dẫn tới trang chi tiết của admin (không phải trang của chủ link)', async () => {
    serve({ 'GET /api/v1/users/me': adminMe, 'GET /api/v1/admin/urls': () => ok(pageOf([adminLink('abc1234')] as never)) });
    renderApp('/admin/links', { authed: true });

    const name = await screen.findByRole('link', { name: 'sho.rt/abc1234' });

    expect(name).toHaveAttribute('href', '/admin/links/abc1234');
  });

  it('hiện link, người tạo, trạng thái và bốn chỉ số của 30 ngày gần nhất, gọi API admin', async () => {
    serveDetail();
    renderApp('/admin/links/abc1234', { authed: true });

    expect(await screen.findByRole('link', { name: 'sho.rt/abc1234' })).toHaveAttribute('href', 'https://sho.rt/abc1234');
    expect(screen.getByText('Người tạo: alice')).toBeInTheDocument();
    await screen.findByText('Tổng click');
    const expected = lastDays(30);
    await waitFor(() => expect(callsTo('GET', '/api/v1/admin/urls/abc1234/stats')).toHaveLength(1));
    expect(callsTo('GET', '/api/v1/admin/urls/abc1234/stats')[0].query.get('from')).toBe(expected.from);
    expect(kpi('Tổng click')).toHaveTextContent('120');
    expect(kpi('Click của người')).toHaveTextContent('100');
    expect(screen.getByRole('navigation', { name: 'Breadcrumb' })).toHaveTextContent('Tất cả link');
    // Không đụng tới API của chủ link (sẽ trả 404 cho link của người khác)
    expect(callsTo('GET', '/api/v1/urls/abc1234/stats')).toHaveLength(0);
  });

  it('link ẩn danh ghi "Ẩn danh" và đổi khoảng ngày thì gọi lại thống kê', async () => {
    serveDetail({ 'GET /api/v1/admin/urls/abc1234': () => ok(adminLink('abc1234', { userId: undefined, createdBy: 'anonymous' })) });
    renderApp('/admin/links/abc1234', { authed: true });
    expect(await screen.findByText('Người tạo: Ẩn danh')).toBeInTheDocument();
    await screen.findByText('Tổng click');

    await userEvent.click(screen.getByRole('button', { name: '7 ngày qua' }));

    await waitFor(() => expect(callsTo('GET', '/api/v1/admin/urls/abc1234/stats').at(-1)!.query.get('from')).toBe(lastDays(7).from));
  });

  it('chặn ngay tại trang chi tiết: bắt buộc lý do, trạng thái và lý do cập nhật, rồi gỡ chặn được', async () => {
    let current = adminLink('abc1234');
    serveDetail({
      'GET /api/v1/admin/urls/abc1234': () => ok(current),
      'PUT /api/v1/admin/urls/abc1234/block': (c) => {
        current = adminLink('abc1234', { status: 'BLOCKED', blockedReason: (c.body as { reason: string }).reason });
        return ok(current);
      },
      'PUT /api/v1/admin/urls/abc1234/unblock': () => {
        current = adminLink('abc1234');
        return ok(current);
      },
    });
    renderApp('/admin/links/abc1234', { authed: true });
    await screen.findByText('Tổng click');

    await userEvent.click(screen.getByRole('button', { name: 'Chặn' }));
    expect(dialog().getByRole('button', { name: 'Chặn link' })).toBeDisabled();
    await userEvent.type(dialog().getByLabelText('Lý do'), 'Lừa đảo');
    await userEvent.click(dialog().getByRole('button', { name: 'Chặn link' }));

    expect(await screen.findByText('Quản trị viên đã chặn: Lừa đảo')).toBeInTheDocument();
    expect(callsTo('PUT', '/api/v1/admin/urls/abc1234/block')[0].body).toEqual({ reason: 'Lừa đảo' });
    expect(screen.queryByRole('button', { name: 'Chặn' })).not.toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Gỡ chặn' }));
    await userEvent.click(dialog().getByRole('button', { name: 'Gỡ chặn link' }));

    await waitFor(() => expect(screen.queryByText(/Quản trị viên đã chặn/)).not.toBeInTheDocument());
    expect(screen.getByRole('button', { name: 'Chặn' })).toBeInTheDocument();
  });

  it('link không tồn tại hoặc đã xoá: trang 404 có đường quay về danh sách', async () => {
    serve({
      'GET /api/v1/users/me': adminMe,
      'GET /api/v1/admin/urls/nope1234': () => fail(404, 'ERR_400', 'Không tìm thấy'),
      'GET /api/v1/admin/urls/nope1234/stats': () => fail(404, 'ERR_400', 'Không tìm thấy'),
    });
    renderApp('/admin/links/nope1234', { authed: true });

    expect(await screen.findByText('Không tìm thấy link')).toBeInTheDocument();
    expect(screen.getByText('Link này không tồn tại hoặc đã bị xoá.')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Quay lại' })).toHaveAttribute('href', '/admin/links');
  });

  it('người dùng thường bị đưa về danh sách link của mình và không gọi API admin', async () => {
    serve({ 'GET /api/v1/users/me': me, 'GET /api/v1/urls': () => ok(pageOf([link('abc1234')])) });
    renderApp('/admin/links/abc1234', { authed: true });

    expect(await screen.findByRole('link', { name: 'sho.rt/abc1234' })).toHaveAttribute('href', '/links/abc1234');
    expect(callsTo('GET', '/api/v1/admin/urls/abc1234')).toHaveLength(0);
  });
});
