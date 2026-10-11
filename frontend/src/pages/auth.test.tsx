import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { tokenStore } from '../api/client';
import { callsTo, created, fail, me, ok, pageOf, renderApp, resetServer, serve, USER } from '../test/helpers';

beforeEach(() => resetServer());
afterEach(() => vi.unstubAllGlobals());

const loginResponse = () => ok({ accessToken: 'at', refreshToken: 'rt', user: USER });
const emptyList = () => ok(pageOf([]));

async function fillLogin(username: string, password: string) {
  await userEvent.type(screen.getByLabelText('Tên đăng nhập'), username);
  await userEvent.type(screen.getByLabelText('Mật khẩu'), password);
}

describe('đăng nhập', () => {
  it('đăng nhập đúng lưu token và vào trang link', async () => {
    serve({ 'POST /api/v1/auth/login': loginResponse, 'GET /api/v1/urls': emptyList });
    renderApp('/login');

    await fillLogin('alice', 'Passw0rd!');
    await userEvent.click(screen.getByRole('button', { name: 'Đăng nhập' }));

    expect(await screen.findByRole('heading', { name: 'Link của tôi', level: 1 })).toBeInTheDocument();
    expect(tokenStore.access()).toBe('at');
    expect(tokenStore.refresh()).toBe('rt');
    expect(callsTo('POST', '/api/v1/auth/login')[0].body).toEqual({ username: 'alice', password: 'Passw0rd!' });
  });

  it('sai mật khẩu hiện lỗi của server, không lưu token và giữ lại tên đăng nhập', async () => {
    serve({ 'POST /api/v1/auth/login': () => fail(401, 'ERR_200', 'Sai tên đăng nhập hoặc mật khẩu') });
    renderApp('/login');

    await fillLogin('alice', 'wrong');
    await userEvent.click(screen.getByRole('button', { name: 'Đăng nhập' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Sai tên đăng nhập hoặc mật khẩu');
    expect(tokenStore.access()).toBeNull();
    expect(screen.getByLabelText('Tên đăng nhập')).toHaveValue('alice');
    expect(screen.getByRole('button', { name: 'Đăng nhập' })).toBeEnabled();
  });

  it('nút đăng nhập bị khoá khi thiếu thông tin', async () => {
    renderApp('/login');

    expect(screen.getByRole('button', { name: 'Đăng nhập' })).toBeDisabled();
    await userEvent.type(screen.getByLabelText('Tên đăng nhập'), 'alice');
    expect(screen.getByRole('button', { name: 'Đăng nhập' })).toBeDisabled();
  });

  it('nút con mắt hiện và ẩn mật khẩu', async () => {
    renderApp('/login');
    const input = screen.getByLabelText('Mật khẩu');
    expect(input).toHaveAttribute('type', 'password');

    await userEvent.click(screen.getByRole('button', { name: 'Hiện mật khẩu' }));
    expect(input).toHaveAttribute('type', 'text');
    await userEvent.click(screen.getByRole('button', { name: 'Ẩn mật khẩu' }));

    expect(input).toHaveAttribute('type', 'password');
  });

  it('bị chuyển từ trang cần đăng nhập thì quay lại đúng trang đó sau khi vào', async () => {
    serve({
      'POST /api/v1/auth/login': loginResponse,
      'GET /api/v1/users/me': me,
    });
    renderApp('/settings');
    expect(await screen.findByRole('heading', { name: 'Chào mừng trở lại' })).toBeInTheDocument();

    await fillLogin('alice', 'Passw0rd!');
    await userEvent.click(screen.getByRole('button', { name: 'Đăng nhập' }));

    expect(await screen.findByRole('heading', { name: 'Cài đặt', level: 1 })).toBeInTheDocument();
  });

  it('đã đăng nhập mà vào /login thì được chuyển thẳng vào trang link', async () => {
    serve({ 'GET /api/v1/users/me': me, 'GET /api/v1/urls': emptyList });
    renderApp('/login', { authed: true });

    expect(await screen.findByRole('heading', { name: 'Link của tôi', level: 1 })).toBeInTheDocument();
  });
});

describe('đăng ký', () => {
  async function fillRegister(username = 'alice', password = 'Passw0rd1') {
    await userEvent.type(screen.getByLabelText('Họ và tên'), 'Alice Nguyen');
    await userEvent.type(screen.getByLabelText('Tên đăng nhập'), username);
    await userEvent.type(screen.getByLabelText('Email'), 'alice@example.com');
    await userEvent.type(screen.getByLabelText('Mật khẩu'), password);
  }

  it('đăng ký xong tự đăng nhập, vào thẳng trang link và chào bằng tên', async () => {
    serve({
      'POST /api/v1/auth/register': () => created(USER),
      'POST /api/v1/auth/login': loginResponse,
      'GET /api/v1/urls': emptyList,
    });
    renderApp('/register');

    await fillRegister();
    await userEvent.click(screen.getByRole('button', { name: 'Tạo tài khoản' }));

    expect(await screen.findByRole('heading', { name: 'Link của tôi', level: 1 })).toBeInTheDocument();
    expect(await screen.findByText('Chào mừng, Alice Nguyen!')).toBeInTheDocument();
    expect(callsTo('POST', '/api/v1/auth/register')[0].body).toEqual({
      fullName: 'Alice Nguyen',
      username: 'alice',
      email: 'alice@example.com',
      password: 'Passw0rd1',
    });
    // đăng nhập bằng đúng thông tin vừa đăng ký
    expect(callsTo('POST', '/api/v1/auth/login')[0].body).toEqual({ username: 'alice', password: 'Passw0rd1' });
  });

  it('lỗi validation của server hiện dưới đúng ô và không đăng nhập', async () => {
    serve({
      'POST /api/v1/auth/register': () =>
        fail(400, 'ERR_001', 'Dữ liệu không hợp lệ', { username: 'Tên đăng nhập quá ngắn', email: 'Email không hợp lệ' }),
    });
    renderApp('/register');

    await fillRegister('ab');
    await userEvent.click(screen.getByRole('button', { name: 'Tạo tài khoản' }));

    expect(await screen.findByText('Tên đăng nhập quá ngắn')).toBeInTheDocument();
    expect(screen.getByText('Email không hợp lệ')).toBeInTheDocument();
    expect(callsTo('POST', '/api/v1/auth/login')).toHaveLength(0);
    expect(screen.getByLabelText('Tên đăng nhập')).toHaveAttribute('aria-invalid', 'true');
  });

  it('tên đăng nhập đã tồn tại (409) hiện thông báo của server', async () => {
    serve({ 'POST /api/v1/auth/register': () => fail(409, 'ERR_101', 'Tên đăng nhập đã được dùng') });
    renderApp('/register');

    await fillRegister();
    await userEvent.click(screen.getByRole('button', { name: 'Tạo tài khoản' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Tên đăng nhập đã được dùng');
  });

  it('danh sách quy tắc mật khẩu sáng dần khi gõ, khớp validation của backend', async () => {
    renderApp('/register');
    const rule = (name: string) => screen.getByText(name).closest('li') as HTMLElement;
    for (const name of ['Ít nhất 8 ký tự', 'Một chữ hoa', 'Một chữ thường', 'Một chữ số']) {
      expect(rule(name)).not.toHaveClass('ok');
    }

    await userEvent.type(screen.getByLabelText('Mật khẩu'), 'abcdefgh');
    expect(rule('Ít nhất 8 ký tự')).toHaveClass('ok');
    expect(rule('Một chữ thường')).toHaveClass('ok');
    expect(rule('Một chữ hoa')).not.toHaveClass('ok');
    expect(rule('Một chữ số')).not.toHaveClass('ok');

    await userEvent.type(screen.getByLabelText('Mật khẩu'), 'A1');
    for (const name of ['Ít nhất 8 ký tự', 'Một chữ hoa', 'Một chữ thường', 'Một chữ số']) {
      expect(rule(name)).toHaveClass('ok');
    }
  });
});

describe('route cần đăng nhập', () => {
  it('chưa đăng nhập thì chuyển sang /login mà không gọi API', async () => {
    renderApp('/dashboard');

    expect(await screen.findByRole('heading', { name: 'Chào mừng trở lại' })).toBeInTheDocument();
  });

  it('F5 với token còn hợp lệ khôi phục phiên', async () => {
    serve({ 'GET /api/v1/users/me': me, 'GET /api/v1/urls': emptyList });
    renderApp('/dashboard', { authed: true });

    expect(await screen.findByRole('heading', { name: 'Link của tôi', level: 1 })).toBeInTheDocument();
    expect(await screen.findByText('Tạo link rút gọn đầu tiên của bạn')).toBeInTheDocument();
  });

  it('F5 với token đã chết đưa về /login', async () => {
    serve({ 'GET /api/v1/users/me': () => fail(401, 'ERR_201', 'Token không hợp lệ') });
    renderApp('/dashboard', { authed: true });

    expect(await screen.findByRole('heading', { name: 'Chào mừng trở lại' })).toBeInTheDocument();
  });

  it('đăng xuất từ menu tài khoản KHÔNG đăng xuất ngay mà hỏi xác nhận trước', async () => {
    serve({
      'GET /api/v1/users/me': me,
      'GET /api/v1/urls': emptyList,
      'POST /api/v1/auth/logout': () => ok(null),
    });
    renderApp('/dashboard', { authed: true });
    await screen.findByRole('heading', { name: 'Link của tôi', level: 1 });

    await userEvent.click(screen.getByRole('button', { name: 'Menu tài khoản' }));
    await userEvent.click(screen.getByRole('menuitem', { name: 'Đăng xuất' }));

    const dialog = within(screen.getByRole('dialog'));
    expect(dialog.getByRole('heading', { name: 'Đăng xuất khỏi Shortly?' })).toBeInTheDocument();
    expect(dialog.getByText(/đăng nhập lại để quản lý link/)).toBeInTheDocument();
    // chưa làm gì cả cho tới khi xác nhận
    expect(callsTo('POST', '/api/v1/auth/logout')).toHaveLength(0);
    expect(tokenStore.access()).toBe('at');
    expect(screen.getByRole('heading', { name: 'Link của tôi', level: 1 })).toBeInTheDocument();
  });

  it('xác nhận đăng xuất: gọi API, xoá token và về trang chủ', async () => {
    serve({
      'GET /api/v1/users/me': me,
      'GET /api/v1/urls': emptyList,
      'POST /api/v1/auth/logout': () => ok(null),
    });
    renderApp('/dashboard', { authed: true });
    await screen.findByRole('heading', { name: 'Link của tôi', level: 1 });

    await userEvent.click(screen.getByRole('button', { name: 'Menu tài khoản' }));
    await userEvent.click(screen.getByRole('menuitem', { name: 'Đăng xuất' }));
    await userEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Đăng xuất' }));

    expect(await screen.findByRole('heading', { name: /Link ngắn gọn/ })).toBeInTheDocument();
    expect(tokenStore.access()).toBeNull();
    expect(callsTo('POST', '/api/v1/auth/logout')).toHaveLength(1);
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('huỷ trong hộp thoại xác nhận: vẫn đăng nhập, không gọi API', async () => {
    serve({ 'GET /api/v1/users/me': me, 'GET /api/v1/urls': emptyList });
    renderApp('/dashboard', { authed: true });
    await screen.findByRole('heading', { name: 'Link của tôi', level: 1 });

    await userEvent.click(screen.getByRole('button', { name: 'Menu tài khoản' }));
    await userEvent.click(screen.getByRole('menuitem', { name: 'Đăng xuất' }));
    await userEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Huỷ' }));

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(tokenStore.access()).toBe('at');
    expect(callsTo('POST', '/api/v1/auth/logout')).toHaveLength(0);
    expect(screen.getByRole('heading', { name: 'Link của tôi', level: 1 })).toBeInTheDocument();
  });

  it('API đăng xuất lỗi (token đã hết hạn) vẫn xoá phiên phía client sau khi xác nhận', async () => {
    serve({
      'GET /api/v1/users/me': me,
      'GET /api/v1/urls': emptyList,
      'POST /api/v1/auth/logout': () => fail(401, 'ERR_201', 'Token không hợp lệ'),
    });
    renderApp('/dashboard', { authed: true });
    await screen.findByRole('heading', { name: 'Link của tôi', level: 1 });

    await userEvent.click(screen.getByRole('button', { name: 'Menu tài khoản' }));
    await userEvent.click(screen.getByRole('menuitem', { name: 'Đăng xuất' }));
    await userEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Đăng xuất' }));

    expect(await screen.findByRole('heading', { name: /Link ngắn gọn/ })).toBeInTheDocument();
    expect(tokenStore.access()).toBeNull();
  });

  it('nút đăng xuất ở thanh trên của trang công khai cũng hỏi xác nhận', async () => {
    serve({ 'GET /api/v1/users/me': me, 'POST /api/v1/auth/logout': () => ok(null) });
    renderApp('/', { authed: true });

    await userEvent.click(await screen.findByRole('button', { name: 'Đăng xuất' }));
    expect(screen.getByRole('dialog')).toBeInTheDocument();
    expect(callsTo('POST', '/api/v1/auth/logout')).toHaveLength(0);
    expect(tokenStore.access()).toBe('at');

    await userEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Đăng xuất' }));

    await waitFor(() => expect(tokenStore.access()).toBeNull());
    expect(callsTo('POST', '/api/v1/auth/logout')).toHaveLength(1);
    expect(await screen.findByRole('link', { name: 'Đăng ký miễn phí' })).toBeInTheDocument();
  });

  it('hộp thoại xác nhận bằng tiếng Anh khi đổi ngôn ngữ', async () => {
    resetServer('en');
    serve({ 'GET /api/v1/users/me': me, 'GET /api/v1/urls': emptyList });
    renderApp('/dashboard', { authed: true });
    await screen.findByRole('heading', { name: 'Links', level: 1 });

    await userEvent.click(screen.getByRole('button', { name: 'Account menu' }));
    await userEvent.click(screen.getByRole('menuitem', { name: 'Log out' }));

    expect(within(screen.getByRole('dialog')).getByRole('heading', { name: 'Log out of Shortly?' })).toBeInTheDocument();
  });

  it('menu tài khoản hiện tên, email và chữ cái đầu', async () => {
    serve({ 'GET /api/v1/users/me': me, 'GET /api/v1/urls': emptyList });
    renderApp('/dashboard', { authed: true });
    await screen.findByRole('heading', { name: 'Link của tôi', level: 1 });

    const avatar = screen.getByRole('button', { name: 'Menu tài khoản' });
    expect(avatar).toHaveTextContent('AN');
    await userEvent.click(avatar);

    expect(screen.getByText('Alice Nguyen')).toBeInTheDocument();
    expect(screen.getByText('alice@example.com')).toBeInTheDocument();
  });
});
