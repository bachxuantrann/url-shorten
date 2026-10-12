import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { AdminUser } from '../api/types';
import { callsTo, fail, mockMatchMedia, ok, pageOf, renderApp, resetServer, serve, USER } from '../test/helpers';

beforeEach(() => {
  resetServer();
  mockMatchMedia(() => false);
});
afterEach(() => vi.unstubAllGlobals());

const ADMIN = { ...USER, id: 'admin-1', username: 'root', fullName: 'Root Admin', roles: ['ROLE_USER', 'ROLE_ADMIN'] };
const adminMe = () => ok(ADMIN);
const dialog = () => within(screen.getByRole('dialog'));

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
const rootUser = user('admin-1', 'root', { roles: ['ROLE_USER', 'ROLE_ADMIN'] });
const rowWith = (text: string) => screen.getByText(text).closest('tr') as HTMLElement;

describe('quản trị: đổi vai trò người dùng', () => {
  it('người dùng thường có nút Cấp quyền admin, admin khác có Gỡ quyền admin, còn tài khoản của mình bị vô hiệu', async () => {
    serve({
      'GET /api/v1/users/me': adminMe,
      'GET /api/v1/admin/users': () => ok(pageOf([rootUser, user('u-1', 'alice'), user('u-2', 'bob', { roles: ['ROLE_USER', 'ROLE_ADMIN'] })] as never)),
    });
    renderApp('/admin/users', { authed: true });
    await screen.findByText('@alice · alice@example.com');

    expect(within(rowWith('@alice · alice@example.com')).getByRole('button', { name: 'Cấp quyền admin alice' })).toBeEnabled();
    expect(within(rowWith('@bob · bob@example.com')).getByRole('button', { name: 'Gỡ quyền admin bob' })).toBeEnabled();
    expect(within(rowWith('@root · root@example.com')).getByRole('button', { name: 'Gỡ quyền admin root' })).toBeDisabled();
  });

  it('cấp quyền: xác nhận (kèm lưu ý về hiệu lực), gửi PUT role ROLE_ADMIN, báo toast và tải lại', async () => {
    let listed = 0;
    serve({
      'GET /api/v1/users/me': adminMe,
      'GET /api/v1/admin/users': () =>
        ok(pageOf([user('u-1', 'alice', listed++ === 0 ? {} : { roles: ['ROLE_USER', 'ROLE_ADMIN'] })] as never)),
      'PUT /api/v1/admin/users/u-1/role': () => ok(user('u-1', 'alice', { roles: ['ROLE_USER', 'ROLE_ADMIN'] })),
    });
    renderApp('/admin/users', { authed: true });
    await screen.findByText('@alice · alice@example.com');

    await userEvent.click(screen.getByRole('button', { name: 'Cấp quyền admin alice' }));
    expect(dialog().getByText(/hiệu lực khi phiên hiện tại của họ hết hạn/)).toBeInTheDocument();
    expect(callsTo('PUT', '/api/v1/admin/users/u-1/role')).toHaveLength(0); // chưa xác nhận thì chưa gửi
    await userEvent.click(dialog().getByRole('button', { name: 'Cấp quyền admin' }));

    expect(await screen.findByText('Đã cập nhật vai trò: giờ là quản trị viên')).toBeInTheDocument();
    expect(callsTo('PUT', '/api/v1/admin/users/u-1/role')[0].body).toEqual({ role: 'ROLE_ADMIN' });
    expect(await screen.findByRole('button', { name: 'Gỡ quyền admin alice' })).toBeInTheDocument();
  });

  it('gỡ quyền: gửi ROLE_USER', async () => {
    serve({
      'GET /api/v1/users/me': adminMe,
      'GET /api/v1/admin/users': () => ok(pageOf([rootUser, user('u-2', 'bob', { roles: ['ROLE_USER', 'ROLE_ADMIN'] })] as never)),
      'PUT /api/v1/admin/users/u-2/role': () => ok(user('u-2', 'bob')),
    });
    renderApp('/admin/users', { authed: true });
    await screen.findByText('@bob · bob@example.com');

    await userEvent.click(screen.getByRole('button', { name: 'Gỡ quyền admin bob' }));
    await userEvent.click(dialog().getByRole('button', { name: 'Gỡ quyền admin' }));

    await waitFor(() => expect(callsTo('PUT', '/api/v1/admin/users/u-2/role')).toHaveLength(1));
    expect(callsTo('PUT', '/api/v1/admin/users/u-2/role')[0].body).toEqual({ role: 'ROLE_USER' });
  });

  it('lỗi từ server (admin cuối cùng) hiện trong hộp thoại và không đóng', async () => {
    serve({
      'GET /api/v1/users/me': adminMe,
      'GET /api/v1/admin/users': () => ok(pageOf([user('u-2', 'bob', { roles: ['ROLE_USER', 'ROLE_ADMIN'] })] as never)),
      'PUT /api/v1/admin/users/u-2/role': () => fail(409, 'ERR_108', 'Phải còn ít nhất một quản trị viên đang hoạt động.'),
    });
    renderApp('/admin/users', { authed: true });
    await screen.findByText('@bob · bob@example.com');

    await userEvent.click(screen.getByRole('button', { name: 'Gỡ quyền admin bob' }));
    await userEvent.click(dialog().getByRole('button', { name: 'Gỡ quyền admin' }));

    expect(await dialog().findByText('Phải còn ít nhất một quản trị viên đang hoạt động.')).toBeInTheDocument();
  });
});
