import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { AdminUrl, AdminUser, BlockedDomain } from '../api/types';
import { callsTo, created, fail, link, me, mockMatchMedia, ok, pageOf, renderApp, resetServer, serve, USER } from '../test/helpers';

beforeEach(() => {
  resetServer();
  mockMatchMedia(() => false);
});
afterEach(() => vi.unstubAllGlobals());

const ADMIN = { ...USER, id: 'admin-1', username: 'root', fullName: 'Root Admin', roles: ['ROLE_USER', 'ROLE_ADMIN'] };
const adminMe = () => ok(ADMIN);

const adminLink = (code: string, extra: Partial<AdminUrl> = {}): AdminUrl => ({
  ...link(code),
  userId: 'u-1',
  createdBy: 'alice',
  updatedBy: 'alice',
  ...extra,
});

const domain = (id: number, name: string, extra: Partial<BlockedDomain> = {}): BlockedDomain => ({
  id,
  domain: name,
  reason: 'phishing',
  createdAt: '2026-10-02T09:00:00',
  createdBy: 'root',
  ...extra,
});

const user = (id: string, username: string, extra: Partial<AdminUser> = {}): AdminUser => ({
  id,
  username,
  email: `${username}@example.com`,
  fullName: `Name of ${username}`,
  status: 'ACTIVE',
  roles: ['ROLE_USER'],
  emailVerified: true,
  createdAt: '2026-09-01T09:00:00',
  ...extra,
});

const rowWith = (text: string) => screen.getByText(text).closest('tr') as HTMLElement;
const dialog = () => within(screen.getByRole('dialog'));

// ===========================
//  Quyền truy cập
// ===========================

describe('khu quản trị: quyền truy cập', () => {
  it('người dùng thường không thấy mục quản trị và bị đưa về danh sách link của mình', async () => {
    serve({ 'GET /api/v1/users/me': me, 'GET /api/v1/urls': () => ok(pageOf([link('abc1234')])) });
    renderApp('/admin/users', { authed: true });

    expect(await screen.findByRole('link', { name: 'sho.rt/abc1234' })).toBeInTheDocument();
    expect(screen.queryByText('Quản trị')).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Tất cả link' })).not.toBeInTheDocument();
    expect(callsTo('GET', '/api/v1/admin/users')).toHaveLength(0);
  });

  it('admin thấy mục Quản trị trong thanh bên, /admin mở trang tất cả link', async () => {
    serve({ 'GET /api/v1/users/me': adminMe, 'GET /api/v1/admin/urls': () => ok(pageOf([adminLink('abc1234')])) });
    renderApp('/admin', { authed: true });

    expect(await screen.findByText('https://example.com/abc1234')).toBeInTheDocument();
    const nav = within(screen.getByRole('navigation', { name: 'Main' }));
    expect(nav.getByText('Quản trị')).toBeInTheDocument();
    expect(nav.getByRole('link', { name: 'Tất cả link' })).toHaveAttribute('href', '/admin/links');
    expect(nav.getByRole('link', { name: 'Domain bị chặn' })).toHaveAttribute('href', '/admin/domains');
    expect(nav.getByRole('link', { name: 'Người dùng' })).toHaveAttribute('href', '/admin/users');
  });
});

// ===========================
//  Tất cả link
// ===========================

describe('quản trị: tất cả link', () => {
  it('hiện link của mọi người, link ẩn danh có nhãn riêng, link bị chặn kèm lý do', async () => {
    serve({
      'GET /api/v1/users/me': adminMe,
      'GET /api/v1/admin/urls': () =>
        ok(
          pageOf([
            adminLink('mine1234', { createdBy: 'alice', clickCount: 1500 }),
            adminLink('anon1234', { userId: undefined, createdBy: 'anonymous' }),
            adminLink('bad12345', { status: 'BLOCKED', blockedReason: 'spam' }),
          ]),
        ),
    });
    renderApp('/admin/links', { authed: true });

    await screen.findByText('https://example.com/mine1234');
    expect(within(rowWith('https://example.com/mine1234')).getByText('alice')).toBeInTheDocument();
    expect(within(rowWith('https://example.com/mine1234')).getByText(/^1[.,]500$/)).toBeInTheDocument();
    expect(within(rowWith('https://example.com/anon1234')).getByText('Ẩn danh')).toBeInTheDocument();
    const blocked = within(rowWith('https://example.com/bad12345'));
    expect(blocked.getByText('Quản trị viên đã chặn: spam')).toBeInTheDocument();
    expect(blocked.getByRole('button', { name: 'Gỡ chặn bad12345' })).toBeInTheDocument();
    expect(within(rowWith('https://example.com/mine1234')).getByRole('button', { name: 'Chặn mine1234' })).toBeInTheDocument();
  });

  it('tìm kiếm và lọc trạng thái được gửi lên server (tìm có trễ, lọc về trang đầu)', async () => {
    serve({ 'GET /api/v1/users/me': adminMe, 'GET /api/v1/admin/urls': () => ok(pageOf([adminLink('abc1234')])) });
    renderApp('/admin/links', { authed: true });
    await screen.findByText('https://example.com/abc1234');

    await userEvent.type(screen.getByRole('searchbox'), 'alice');
    await userEvent.click(screen.getByRole('button', { name: 'Bị chặn' }));

    await waitFor(() => {
      const last = callsTo('GET', '/api/v1/admin/urls').at(-1)!;
      expect(last.query.get('keyword')).toBe('alice');
      expect(last.query.get('status')).toBe('BLOCKED');
      expect(last.query.get('page')).toBe('0');
    });
  });

  it('chặn link: bắt buộc nhập lý do, gửi PUT block và dòng chuyển sang "Bị chặn" ngay', async () => {
    serve({
      'GET /api/v1/users/me': adminMe,
      'GET /api/v1/admin/urls': () => ok(pageOf([adminLink('abc1234')])),
      'PUT /api/v1/admin/urls/abc1234/block': (c) =>
        ok(adminLink('abc1234', { status: 'BLOCKED', blockedReason: (c.body as { reason: string }).reason })),
    });
    renderApp('/admin/links', { authed: true });
    await screen.findByText('https://example.com/abc1234');

    await userEvent.click(screen.getByRole('button', { name: 'Chặn abc1234' }));
    const confirm = dialog().getByRole('button', { name: 'Chặn link' });
    expect(confirm).toBeDisabled(); // chưa có lý do
    await userEvent.type(dialog().getByLabelText('Lý do'), '  Lừa đảo  ');
    await userEvent.click(confirm);

    await waitFor(() => expect(callsTo('PUT', '/api/v1/admin/urls/abc1234/block')).toHaveLength(1));
    expect(callsTo('PUT', '/api/v1/admin/urls/abc1234/block')[0].body).toEqual({ reason: 'Lừa đảo' });
    expect(await screen.findByText('Quản trị viên đã chặn: Lừa đảo')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Gỡ chặn abc1234' })).toBeInTheDocument();
    expect(screen.getByText('Đã chặn link')).toBeInTheDocument();
  });

  it('lỗi từ server khi chặn hiện trong hộp thoại và không đổi dòng', async () => {
    serve({
      'GET /api/v1/users/me': adminMe,
      'GET /api/v1/admin/urls': () => ok(pageOf([adminLink('abc1234')])),
      'PUT /api/v1/admin/urls/abc1234/block': () => fail(404, 'ERR_400', 'Link not found'),
    });
    renderApp('/admin/links', { authed: true });
    await screen.findByText('https://example.com/abc1234');

    await userEvent.click(screen.getByRole('button', { name: 'Chặn abc1234' }));
    await userEvent.type(dialog().getByLabelText('Lý do'), 'spam');
    await userEvent.click(dialog().getByRole('button', { name: 'Chặn link' }));

    expect(await dialog().findByRole('alert')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Chặn abc1234' })).toBeInTheDocument();
  });

  it('gỡ chặn link: xác nhận rồi gửi PUT unblock', async () => {
    serve({
      'GET /api/v1/users/me': adminMe,
      'GET /api/v1/admin/urls': () => ok(pageOf([adminLink('bad12345', { status: 'BLOCKED', blockedReason: 'spam' })])),
      'PUT /api/v1/admin/urls/bad12345/unblock': () => ok(adminLink('bad12345')),
    });
    renderApp('/admin/links', { authed: true });
    await screen.findByText('https://example.com/bad12345');

    await userEvent.click(screen.getByRole('button', { name: 'Gỡ chặn bad12345' }));
    await userEvent.click(dialog().getByRole('button', { name: 'Gỡ chặn link' }));

    expect(await screen.findByRole('button', { name: 'Chặn bad12345' })).toBeInTheDocument();
    expect(screen.queryByText(/Quản trị viên đã chặn/)).not.toBeInTheDocument();
    expect(callsTo('PUT', '/api/v1/admin/urls/bad12345/unblock')).toHaveLength(1);
  });

  it('trên màn hình hẹp dùng thẻ thay vì bảng và vẫn chặn được', async () => {
    mockMatchMedia(() => true);
    serve({ 'GET /api/v1/users/me': adminMe, 'GET /api/v1/admin/urls': () => ok(pageOf([adminLink('abc1234')])) });
    renderApp('/admin/links', { authed: true });

    await screen.findByText('https://example.com/abc1234');
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Chặn abc1234' })).toBeInTheDocument();
  });
});

// ===========================
//  Domain bị chặn
// ===========================

describe('quản trị: domain bị chặn', () => {
  it('liệt kê domain với lý do, và báo khi không có lý do', async () => {
    serve({
      'GET /api/v1/users/me': adminMe,
      'GET /api/v1/admin/blocked-domains': () => ok(pageOf([domain(1, 'evil.com'), domain(2, 'bad.org', { reason: null })] as never)),
    });
    renderApp('/admin/domains', { authed: true });

    await screen.findByText('evil.com');
    expect(within(rowWith('evil.com')).getByText('phishing')).toBeInTheDocument();
    expect(within(rowWith('bad.org')).getByText('Không có lý do')).toBeInTheDocument();
    expect(screen.getByText('2 domain')).toBeInTheDocument();
  });

  it('thêm domain: mặc định chặn luôn link hiện có, báo số link đã chặn và tải lại danh sách', async () => {
    let listed = 0;
    serve({
      'GET /api/v1/users/me': adminMe,
      'GET /api/v1/admin/blocked-domains': () => {
        listed++;
        return ok(pageOf((listed > 1 ? [domain(1, 'evil.com')] : []) as never));
      },
      'POST /api/v1/admin/blocked-domains': () => created(domain(1, 'evil.com', { blockedLinks: 3 })),
    });
    renderApp('/admin/domains', { authed: true });
    await screen.findByText('Chưa chặn domain nào');

    await userEvent.click(screen.getAllByRole('button', { name: 'Chặn một domain' })[0]);
    const form = dialog();
    expect(form.getByRole('checkbox')).toBeChecked();
    expect(form.getByRole('button', { name: 'Chặn domain' })).toBeDisabled();
    await userEvent.type(form.getByLabelText('Domain'), ' evil.com ');
    await userEvent.type(form.getByLabelText(/Lý do/), 'lừa đảo');
    await userEvent.click(form.getByRole('button', { name: 'Chặn domain' }));

    expect(await screen.findByText('Đã chặn domain và 3 link hiện có')).toBeInTheDocument();
    expect(callsTo('POST', '/api/v1/admin/blocked-domains')[0].body).toEqual({
      domain: 'evil.com',
      reason: 'lừa đảo',
      blockExistingLinks: true,
    });
    expect(await screen.findByText('evil.com')).toBeInTheDocument();
  });

  it('bỏ chọn chặn link hiện có thì gửi blockExistingLinks=false và lý do trống bị bỏ qua', async () => {
    serve({
      'GET /api/v1/users/me': adminMe,
      'GET /api/v1/admin/blocked-domains': () => ok(pageOf([])),
      'POST /api/v1/admin/blocked-domains': () => created(domain(1, 'evil.com', { blockedLinks: 0 })),
    });
    renderApp('/admin/domains', { authed: true });
    await screen.findByText('Chưa chặn domain nào');

    await userEvent.click(screen.getAllByRole('button', { name: 'Chặn một domain' })[0]);
    await userEvent.type(dialog().getByLabelText('Domain'), 'evil.com');
    await userEvent.click(dialog().getByRole('checkbox'));
    await userEvent.click(dialog().getByRole('button', { name: 'Chặn domain' }));

    expect(await screen.findByText('Đã chặn domain')).toBeInTheDocument();
    expect(callsTo('POST', '/api/v1/admin/blocked-domains')[0].body).toEqual({ domain: 'evil.com', blockExistingLinks: false });
  });

  it('domain sai cú pháp: lỗi theo field hiện ngay dưới ô nhập', async () => {
    serve({
      'GET /api/v1/users/me': adminMe,
      'GET /api/v1/admin/blocked-domains': () => ok(pageOf([])),
      'POST /api/v1/admin/blocked-domains': () =>
        fail(400, 'ERR_001', 'Dữ liệu không hợp lệ', { domain: 'Domain không hợp lệ' }),
    });
    renderApp('/admin/domains', { authed: true });
    await screen.findByText('Chưa chặn domain nào');

    await userEvent.click(screen.getAllByRole('button', { name: 'Chặn một domain' })[0]);
    await userEvent.type(dialog().getByLabelText('Domain'), 'not a domain');
    await userEvent.click(dialog().getByRole('button', { name: 'Chặn domain' }));

    expect(await dialog().findByText('Domain không hợp lệ')).toBeInTheDocument();
  });

  it('gỡ domain: xác nhận rồi gọi DELETE và tải lại', async () => {
    let listed = 0;
    serve({
      'GET /api/v1/users/me': adminMe,
      'GET /api/v1/admin/blocked-domains': () => {
        listed++;
        return ok(pageOf((listed > 1 ? [] : [domain(7, 'evil.com')]) as never));
      },
      'DELETE /api/v1/admin/blocked-domains/7': () => ok(null),
    });
    renderApp('/admin/domains', { authed: true });
    await screen.findByText('evil.com');

    await userEvent.click(screen.getByRole('button', { name: 'Gỡ bỏ evil.com' }));
    expect(dialog().getByText(/vẫn bị chặn cho tới khi bạn gỡ chặn/)).toBeInTheDocument();
    await userEvent.click(dialog().getByRole('button', { name: 'Gỡ domain' }));

    expect(await screen.findByText('Chưa chặn domain nào')).toBeInTheDocument();
    expect(callsTo('DELETE', '/api/v1/admin/blocked-domains/7')).toHaveLength(1);
  });
});

// ===========================
//  Người dùng
// ===========================

describe('quản trị: người dùng', () => {
  const users = () =>
    ok(
      pageOf([
        user('admin-1', 'root', { roles: ['ROLE_USER', 'ROLE_ADMIN'], lastLoginAt: '2026-10-10T08:00:00' }),
        user('u-2', 'bob'),
        user('u-3', 'carol', { status: 'BANNED' }),
      ] as never),
    );

  it('liệt kê người dùng với vai trò, trạng thái và lần đăng nhập gần nhất', async () => {
    serve({ 'GET /api/v1/users/me': adminMe, 'GET /api/v1/admin/users': users });
    renderApp('/admin/users', { authed: true });

    await screen.findByText('Name of bob');
    const bob = within(rowWith('Name of bob'));
    expect(bob.getByText(/@bob · bob@example.com/)).toBeInTheDocument();
    expect(bob.getByText('Đang hoạt động')).toBeInTheDocument();
    expect(bob.getByText('Chưa từng')).toBeInTheDocument();
    expect(within(rowWith('Name of carol')).getByText('Đã khoá')).toBeInTheDocument();
    const root = within(rowWith('Name of root'));
    expect(root.getByText('Quản trị viên')).toBeInTheDocument();
    expect(root.getByText('Bạn')).toBeInTheDocument();
  });

  it('cột thao tác của bảng người dùng đủ rộng cho nhãn dài nhất (jsdom không đo được layout nên kiểm hợp đồng CSS)', async () => {
    serve({ 'GET /api/v1/users/me': adminMe, 'GET /api/v1/admin/users': users });
    renderApp('/admin/users', { authed: true });
    await screen.findByText('Name of bob');

    // Lỗi đã gặp ở trình duyệt thật: nút "Mở khoá tài khoản" (~160px) tràn ra khỏi cột 132px, sinh thanh cuộn ngang
    const header = screen.getAllByRole('columnheader').at(-1)!;
    expect(header).toHaveClass('col-action', 'col-action-wide');
    const css = readFileSync(resolve(__dirname, '../styles.css'), 'utf8');
    const width = /\.data-table \.col-action\.col-action-wide\s*\{\s*width:\s*(\d+)px/.exec(css);
    expect(width, 'rule .col-action-wide must exist').not.toBeNull();
    expect(Number(width![1])).toBeGreaterThanOrEqual(200);
  });

  it('không cho admin tự khoá chính mình', async () => {
    serve({ 'GET /api/v1/users/me': adminMe, 'GET /api/v1/admin/users': users });
    renderApp('/admin/users', { authed: true });
    await screen.findByText('Name of bob');

    expect(screen.getByRole('button', { name: 'Khoá tài khoản root' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Khoá tài khoản bob' })).toBeEnabled();
  });

  it('khoá tài khoản: xác nhận rồi gửi PUT lock và tải lại', async () => {
    serve({
      'GET /api/v1/users/me': adminMe,
      'GET /api/v1/admin/users': users,
      'PUT /api/v1/admin/users/u-2/lock': () => ok(null),
    });
    renderApp('/admin/users', { authed: true });
    await screen.findByText('Name of bob');
    const before = callsTo('GET', '/api/v1/admin/users').length;

    await userEvent.click(screen.getByRole('button', { name: 'Khoá tài khoản bob' }));
    expect(screen.getByRole('dialog', { name: 'Khoá bob?' })).toBeInTheDocument();
    await userEvent.click(dialog().getByRole('button', { name: 'Khoá tài khoản' }));

    expect(await screen.findByText('Đã khoá tài khoản')).toBeInTheDocument();
    expect(callsTo('PUT', '/api/v1/admin/users/u-2/lock')).toHaveLength(1);
    await waitFor(() => expect(callsTo('GET', '/api/v1/admin/users').length).toBeGreaterThan(before));
  });

  it('mở khoá tài khoản đang bị khoá', async () => {
    serve({
      'GET /api/v1/users/me': adminMe,
      'GET /api/v1/admin/users': users,
      'PUT /api/v1/admin/users/u-3/unlock': () => ok(null),
    });
    renderApp('/admin/users', { authed: true });
    await screen.findByText('Name of carol');

    await userEvent.click(screen.getByRole('button', { name: 'Mở khoá tài khoản carol' }));
    await userEvent.click(dialog().getByRole('button', { name: 'Mở khoá tài khoản' }));

    expect(await screen.findByText('Đã mở khoá tài khoản')).toBeInTheDocument();
    expect(callsTo('PUT', '/api/v1/admin/users/u-3/unlock')).toHaveLength(1);
  });

  it('lọc theo trạng thái "Đã khoá" gửi status=BANNED', async () => {
    serve({ 'GET /api/v1/users/me': adminMe, 'GET /api/v1/admin/users': users });
    renderApp('/admin/users', { authed: true });
    await screen.findByText('Name of bob');

    await userEvent.click(screen.getByRole('button', { name: 'Đã khoá' }));

    await waitFor(() => expect(callsTo('GET', '/api/v1/admin/users').at(-1)!.query.get('status')).toBe('BANNED'));
  });
});
