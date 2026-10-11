import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { callsTo, fail, me, ok, pageOf, renderApp, resetServer, serve, USER } from '../test/helpers';

beforeEach(() => resetServer());
afterEach(() => vi.unstubAllGlobals());

function serveSettings(extra: Parameters<typeof serve>[0] = {}) {
  serve({ 'GET /api/v1/users/me': me, 'GET /api/v1/urls': () => ok(pageOf([])), ...extra });
}

describe('cài đặt: hồ sơ', () => {
  it('điền sẵn thông tin hiện tại và không cho sửa tên đăng nhập', async () => {
    serveSettings();
    renderApp('/settings', { authed: true });

    expect(await screen.findByLabelText('Họ và tên')).toHaveValue('Alice Nguyen');
    expect(screen.getByLabelText('Email')).toHaveValue('alice@example.com');
    expect(screen.getByLabelText('Tên đăng nhập')).toHaveValue('alice');
    expect(screen.getByLabelText('Tên đăng nhập')).toHaveAttribute('readonly');
    // Tiêu đề cập nhật trong một effect sau khi render: đợi thay vì kiểm ngay
    await waitFor(() => expect(document.title).toBe('Cài đặt · Shortly'));
  });

  it('lưu hồ sơ gửi PUT, báo thành công và cập nhật tên trong menu tài khoản', async () => {
    serveSettings({
      'PUT /api/v1/users/me': () => ok({ ...USER, fullName: 'Alice Tran', email: 'tran@example.com' }),
    });
    renderApp('/settings', { authed: true });
    const name = await screen.findByLabelText('Họ và tên');

    await userEvent.clear(name);
    await userEvent.type(name, 'Alice Tran');
    await userEvent.clear(screen.getByLabelText('Email'));
    await userEvent.type(screen.getByLabelText('Email'), 'tran@example.com');
    await userEvent.click(screen.getByRole('button', { name: 'Lưu thay đổi' }));

    expect(await screen.findByText('Đã cập nhật hồ sơ')).toBeInTheDocument();
    expect(callsTo('PUT', '/api/v1/users/me')[0].body).toEqual({ fullName: 'Alice Tran', email: 'tran@example.com' });
    await userEvent.click(screen.getByRole('button', { name: 'Menu tài khoản' }));
    expect(screen.getByText('tran@example.com')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Menu tài khoản' })).toHaveTextContent('AT');
  });

  it('email đã tồn tại hiện lỗi dưới đúng ô', async () => {
    serveSettings({ 'PUT /api/v1/users/me': () => fail(409, 'ERR_102', 'Email đã được dùng', { email: 'Email đã được dùng' }) });
    renderApp('/settings', { authed: true });
    const email = await screen.findByLabelText('Email');

    await userEvent.type(email, 'x');
    await userEvent.click(screen.getByRole('button', { name: 'Lưu thay đổi' }));

    expect(await screen.findByLabelText('Email')).toHaveAttribute('aria-invalid', 'true');
    // lỗi server không khoá nút: người dùng sửa lại rồi lưu tiếp được
    expect(screen.getByRole('button', { name: 'Lưu thay đổi' })).toBeEnabled();
  });

  it('nút Lưu bị khoá khi chưa sửa gì', async () => {
    serveSettings();
    renderApp('/settings', { authed: true });

    await screen.findByLabelText('Họ và tên');

    expect(screen.getByRole('button', { name: 'Lưu thay đổi' })).toBeDisabled();
  });

  it('bật khi có thay đổi ở bất kỳ ô nào và khoá lại khi người dùng sửa về giá trị cũ', async () => {
    serveSettings();
    renderApp('/settings', { authed: true });
    const name = await screen.findByLabelText('Họ và tên');
    const save = screen.getByRole('button', { name: 'Lưu thay đổi' });

    await userEvent.type(name, 'X');
    expect(save).toBeEnabled();
    await userEvent.type(name, '{Backspace}');
    expect(save).toBeDisabled();

    await userEvent.type(screen.getByLabelText('Email'), 'm');
    expect(save).toBeEnabled();
  });

  it('chỉ thêm khoảng trắng đầu/cuối không tính là thay đổi', async () => {
    serveSettings();
    renderApp('/settings', { authed: true });
    const name = await screen.findByLabelText('Họ và tên');

    await userEvent.type(name, '   ');

    expect(screen.getByRole('button', { name: 'Lưu thay đổi' })).toBeDisabled();
  });

  it('lưu thành công thì nút khoá lại (hồ sơ mới trở thành giá trị hiện tại)', async () => {
    serveSettings({ 'PUT /api/v1/users/me': () => ok({ ...USER, fullName: 'Alice Tran' }) });
    renderApp('/settings', { authed: true });
    const name = await screen.findByLabelText('Họ và tên');
    await userEvent.clear(name);
    await userEvent.type(name, 'Alice Tran');

    await userEvent.click(screen.getByRole('button', { name: 'Lưu thay đổi' }));

    expect(await screen.findByText('Đã cập nhật hồ sơ')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Lưu thay đổi' })).toBeDisabled();
  });

  it('không lưu được khi tên bị xoá trống', async () => {
    serveSettings();
    renderApp('/settings', { authed: true });

    await userEvent.clear(await screen.findByLabelText('Họ và tên'));

    expect(screen.getByRole('button', { name: 'Lưu thay đổi' })).toBeDisabled();
  });
});

describe('cài đặt: đổi mật khẩu', () => {
  async function fillPasswords(old = 'OldPass1', next = 'NewPass12', confirm = 'NewPass12') {
    await userEvent.type(await screen.findByLabelText('Mật khẩu hiện tại'), old);
    await userEvent.type(screen.getByLabelText('Mật khẩu mới'), next);
    await userEvent.type(screen.getByLabelText('Nhập lại mật khẩu mới'), confirm);
  }

  it('đổi thành công gửi đủ ba trường, báo thành công và xoá sạch các ô', async () => {
    serveSettings({ 'PUT /api/v1/users/me/change-password': () => ok(null) });
    renderApp('/settings', { authed: true });

    await fillPasswords();
    await userEvent.click(screen.getByRole('button', { name: 'Đổi mật khẩu' }));

    expect(await screen.findByText('Đã đổi mật khẩu')).toBeInTheDocument();
    expect(callsTo('PUT', '/api/v1/users/me/change-password')[0].body).toEqual({
      oldPassword: 'OldPass1',
      newPassword: 'NewPass12',
      confirmPassword: 'NewPass12',
    });
    expect(screen.getByLabelText('Mật khẩu hiện tại')).toHaveValue('');
    expect(screen.getByLabelText('Mật khẩu mới')).toHaveValue('');
  });

  it('mật khẩu hiện tại sai hiện thông báo của server và giữ nguyên các ô', async () => {
    serveSettings({ 'PUT /api/v1/users/me/change-password': () => fail(400, 'ERR_105', 'Mật khẩu hiện tại không đúng') });
    renderApp('/settings', { authed: true });

    await fillPasswords('Wrong123');
    await userEvent.click(screen.getByRole('button', { name: 'Đổi mật khẩu' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Mật khẩu hiện tại không đúng');
    expect(screen.getByLabelText('Mật khẩu hiện tại')).toHaveValue('Wrong123');
  });

  it('nút bị khoá khi còn thiếu ô', async () => {
    serveSettings();
    renderApp('/settings', { authed: true });

    await userEvent.type(await screen.findByLabelText('Mật khẩu hiện tại'), 'OldPass1');

    expect(screen.getByRole('button', { name: 'Đổi mật khẩu' })).toBeDisabled();
  });
});

describe('cài đặt: giao diện và ngôn ngữ', () => {
  it('chọn giao diện tối đặt data-theme và đánh dấu nút đang chọn', async () => {
    serveSettings();
    renderApp('/settings', { authed: true });
    await screen.findByRole('heading', { name: 'Giao diện', level: 2 });

    const group = screen.getByRole('group', { name: 'Đổi giao diện' });
    await userEvent.click(within(group).getByRole('button', { name: /Tối/ }));

    expect(document.documentElement).toHaveAttribute('data-theme', 'dark');
    expect(within(group).getByRole('button', { name: /Tối/ })).toHaveAttribute('aria-pressed', 'true');
    expect(within(group).getByRole('button', { name: /Sáng/ })).toHaveAttribute('aria-pressed', 'false');
  });

  it('đổi ngôn ngữ sang tiếng Anh cập nhật cả trang', async () => {
    serveSettings();
    renderApp('/settings', { authed: true });
    await screen.findByRole('heading', { name: 'Cài đặt', level: 1 });

    const group = screen.getByRole('group', { name: 'Ngôn ngữ' });
    await userEvent.click(within(group).getByRole('button', { name: 'English' }));

    expect(screen.getByRole('heading', { name: 'Settings', level: 1 })).toBeInTheDocument();
    expect(localStorage.getItem('lang')).toBe('en');
  });
});
