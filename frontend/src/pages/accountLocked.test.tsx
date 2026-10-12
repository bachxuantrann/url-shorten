import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { tokenStore } from '../api/client';
import { SESSION_CHECK_MS } from '../auth/AuthProvider';
import { callsTo, fail, link, me, ok, pageOf, renderApp, resetServer, serve, USER } from '../test/helpers';

beforeEach(() => resetServer());
afterEach(() => vi.restoreAllMocks());

const LOCKED = () => fail(403, 'ERR_104', 'Your account has been suspended.');
const lockedDialog = () => screen.getByRole('dialog', { name: 'Tài khoản của bạn đã bị khoá' });

describe('tài khoản bị admin khoá khi đang đăng nhập', () => {
  it('request kế tiếp nhận 403 ERR_104: hiện hộp thoại giải thích, xoá phiên và đưa về trang đăng nhập', async () => {
    serve({ 'GET /api/v1/users/me': me, 'GET /api/v1/urls': LOCKED });
    renderApp('/dashboard', { authed: true });

    expect(await screen.findByRole('dialog', { name: 'Tài khoản của bạn đã bị khoá' })).toBeInTheDocument();
    expect(within(lockedDialog()).getByText(/Quản trị viên vừa khoá tài khoản này/)).toBeInTheDocument();
    expect(tokenStore.access()).toBeNull();
    expect(tokenStore.refresh()).toBeNull();
    // Không thử refresh: 403 không phải 401
    expect(callsTo('POST', '/api/v1/auth/refresh-token')).toHaveLength(0);

    await userEvent.click(within(lockedDialog()).getByRole('button', { name: 'Về trang đăng nhập' }));

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(await screen.findByRole('button', { name: /Đăng nhập/ })).toBeInTheDocument();
  });

  it('access token hết hạn rồi refresh bị 403 ERR_104 (người nghỉ lâu quay lại): vẫn hiện hộp thoại, không đá ra âm thầm', async () => {
    serve({
      'GET /api/v1/users/me': me,
      'GET /api/v1/urls': () => fail(401, 'ERR_201', 'Token hết hạn'),
      'POST /api/v1/auth/refresh-token': LOCKED,
    });
    renderApp('/dashboard', { authed: true });

    expect(await screen.findByRole('dialog', { name: 'Tài khoản của bạn đã bị khoá' })).toBeInTheDocument();
    expect(callsTo('POST', '/api/v1/auth/refresh-token')).toHaveLength(1);
    expect(tokenStore.access()).toBeNull();
  });

  it('phiên hết hạn bình thường (refresh bị từ chối vì lý do khác) thì KHÔNG hiện hộp thoại khoá', async () => {
    serve({
      'GET /api/v1/users/me': me,
      'GET /api/v1/urls': () => fail(401, 'ERR_201', 'Token hết hạn'),
      'POST /api/v1/auth/refresh-token': () => fail(401, 'ERR_203', 'Refresh token không khớp'),
    });
    renderApp('/dashboard', { authed: true });

    expect(await screen.findByRole('button', { name: /Đăng nhập/ })).toBeInTheDocument();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('403 vì thiếu quyền (mã khác ERR_104) không bị nhầm là bị khoá: giữ phiên, không hộp thoại', async () => {
    serve({
      'GET /api/v1/users/me': me,
      'GET /api/v1/urls': () => fail(403, 'ERR_300', 'Không đủ quyền'),
    });
    renderApp('/dashboard', { authed: true });

    expect(await screen.findByText(/Không đủ quyền/)).toBeInTheDocument();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(tokenStore.access()).not.toBeNull();
  });

  it('đăng nhập vào tài khoản đã bị khoá chỉ hiện lỗi trong form, không bật hộp thoại', async () => {
    serve({ 'POST /api/v1/auth/login': () => fail(403, 'ERR_104', 'Tài khoản của bạn đã bị tạm khoá.') });
    renderApp('/login');

    await userEvent.type(await screen.findByLabelText('Tên đăng nhập'), 'alice');
    await userEvent.type(screen.getByLabelText('Mật khẩu'), 'Passw0rd!');
    await userEvent.click(screen.getByRole('button', { name: /^Đăng nhập$/ }));

    expect(await screen.findByText('Tài khoản của bạn đã bị tạm khoá.')).toBeInTheDocument();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });
});

describe('kiểm tra phiên định kỳ (để người đứng yên cũng biết sớm)', () => {
  it('đăng ký một chu kỳ kiểm tra và dọn nó khi đăng xuất/gỡ component', async () => {
    const setSpy = vi.spyOn(window, 'setInterval');
    const clearSpy = vi.spyOn(window, 'clearInterval');
    serve({ 'GET /api/v1/users/me': me, 'GET /api/v1/urls': () => ok(pageOf([link('abc1234')])) });
    const { unmount } = renderApp('/dashboard', { authed: true });
    await screen.findByRole('link', { name: 'sho.rt/abc1234' });

    expect(setSpy.mock.calls.some(([, ms]) => ms === SESSION_CHECK_MS)).toBe(true);
    unmount();
    expect(clearSpy).toHaveBeenCalled();
  });

  it('khi quay lại tab mà tài khoản đã bị khoá thì hiện hộp thoại ngay, không cần thao tác gì', async () => {
    let locked = false;
    serve({
      'GET /api/v1/users/me': () => (locked ? LOCKED() : ok(USER)),
      'GET /api/v1/urls': () => ok(pageOf([link('abc1234')])),
    });
    renderApp('/dashboard', { authed: true });
    await screen.findByRole('link', { name: 'sho.rt/abc1234' });
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();

    locked = true; // admin khoá trong lúc người dùng đứng yên
    await act(async () => {
      document.dispatchEvent(new Event('visibilitychange'));
    });

    expect(await screen.findByRole('dialog', { name: 'Tài khoản của bạn đã bị khoá' })).toBeInTheDocument();
    expect(tokenStore.access()).toBeNull();
  });

  it('lỗi mạng khi kiểm tra định kỳ bị bỏ qua: không đăng xuất, không hộp thoại', async () => {
    let down = false;
    serve({
      'GET /api/v1/users/me': () => {
        if (down) throw new TypeError('Failed to fetch');
        return ok(USER);
      },
      'GET /api/v1/urls': () => ok(pageOf([link('abc1234')])),
    });
    renderApp('/dashboard', { authed: true });
    await screen.findByRole('link', { name: 'sho.rt/abc1234' });

    down = true;
    await act(async () => {
      document.dispatchEvent(new Event('visibilitychange'));
    });

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(tokenStore.access()).not.toBeNull();
    expect(screen.getByRole('link', { name: 'sho.rt/abc1234' })).toBeInTheDocument();
  });
});
