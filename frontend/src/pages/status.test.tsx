import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fail, fetchMock, ok, renderApp, resetServer } from '../test/helpers';

const assign = vi.fn();

beforeEach(() => {
  resetServer();
  assign.mockReset();
  vi.stubGlobal('location', { ...window.location, assign });
});
afterEach(() => vi.unstubAllGlobals());

async function submitPassword(password: string) {
  await userEvent.type(screen.getByLabelText('Mật khẩu'), password);
  await userEvent.click(screen.getByRole('button', { name: 'Mở khoá link' }));
}

describe('trang nhập mật khẩu của link', () => {
  it('mở được khi chưa đăng nhập và không gọi API nào lúc tải trang', () => {
    renderApp('/unlock/abc1234');

    expect(screen.getByRole('heading', { name: 'Link này được bảo vệ' })).toBeInTheDocument();
    expect(fetchMock).not.toHaveBeenCalled();
    expect(document.title).toBe('Link này được bảo vệ · Shortly');
  });

  it('đúng mật khẩu thì chuyển hướng tới đích và không gửi token', async () => {
    fetchMock.mockResolvedValue(ok({ originalUrl: 'https://example.com/secret' }));
    renderApp('/unlock/abc1234');

    await submitPassword('right-pw');

    await vi.waitFor(() => expect(assign).toHaveBeenCalledWith('https://example.com/secret'));
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe('/api/v1/public/urls/abc1234/unlock');
    expect(init.method).toBe('POST');
    expect(JSON.parse(init.body)).toEqual({ password: 'right-pw' });
    expect(init.headers.Authorization).toBeUndefined();
  });

  it('sai mật khẩu (ERR_409) hiện lỗi và không chuyển hướng', async () => {
    fetchMock.mockResolvedValue(fail(403, 'ERR_409', 'Mật khẩu không đúng'));
    renderApp('/unlock/abc1234');

    await submitPassword('wrong');

    expect(await screen.findByRole('alert')).toHaveTextContent('Mật khẩu không đúng');
    expect(assign).not.toHaveBeenCalled();
  });

  it('bị giới hạn thử (429) hiện thời gian chờ', async () => {
    fetchMock.mockResolvedValue(fail(429, 'ERR_429', 'x', null, { 'Retry-After': '120' }));
    renderApp('/unlock/abc1234');

    await submitPassword('pw');

    expect(await screen.findByRole('alert')).toHaveTextContent('Thử lại sau 120 giây');
  });

  it('không bao giờ điều hướng tới đích không phải http/https', async () => {
    for (const bad of ['javascript:alert(1)', 'data:text/html,<script>1</script>', 'not a url']) {
      fetchMock.mockResolvedValue(ok({ originalUrl: bad }));
      const view = renderApp('/unlock/abc1234');

      await submitPassword('pw');

      expect(await screen.findByRole('alert')).toHaveTextContent('Đích không phải là địa chỉ web hợp lệ.');
      view.unmount();
    }
    expect(assign).not.toHaveBeenCalled();
  });

  it('link không còn (404) hiện thông báo của server', async () => {
    fetchMock.mockResolvedValue(fail(404, 'ERR_400', 'Không tìm thấy link'));
    renderApp('/unlock/abc1234');

    await submitPassword('pw');

    expect(await screen.findByRole('alert')).toHaveTextContent('Không tìm thấy link');
  });

  it('nút bị khoá khi chưa nhập mật khẩu', () => {
    renderApp('/unlock/abc1234');

    expect(screen.getByRole('button', { name: 'Mở khoá link' })).toBeDisabled();
  });
});

describe('trang link không dùng được', () => {
  it.each([
    ['not-found', '404', 'Chúng tôi không tìm thấy link này', /bị gõ sai/],
    ['gone', '410', 'Link này không còn khả dụng', /hết hạn hoặc bị chủ sở hữu xoá/],
    ['blocked', '403', 'Link này đã bị chặn', /không an toàn/],
  ])('lý do %s hiện mã %s, tiêu đề và giải thích riêng', (reason, code, title, text) => {
    renderApp(`/unavailable/${reason}`);

    expect(screen.getByRole('heading', { name: title })).toBeInTheDocument();
    expect(screen.getByText(code)).toBeInTheDocument();
    expect(screen.getByText(text)).toBeInTheDocument();
    expect(document.title).toBe(`${title} · Shortly`);
    // không gọi API nào: trang này chỉ hiển thị
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('có lối đi tiếp: về trang chủ và tạo link của riêng mình', () => {
    renderApp('/unavailable/gone');

    expect(screen.getByRole('link', { name: 'Về trang chủ' })).toHaveAttribute('href', '/');
    expect(screen.getByRole('link', { name: /Tạo link rút gọn của riêng bạn/ })).toHaveAttribute('href', '/');
  });

  it('lý do lạ rơi về "không tìm thấy" thay vì vỡ trang', () => {
    renderApp('/unavailable/whatever');

    expect(screen.getByRole('heading', { name: 'Chúng tôi không tìm thấy link này' })).toBeInTheDocument();
  });

  it('trang bằng tiếng Anh khi đổi ngôn ngữ', () => {
    resetServer('en');
    renderApp('/unavailable/blocked');

    expect(screen.getByRole('heading', { name: 'This link was blocked' })).toBeInTheDocument();
  });
});

describe('đường dẫn không tồn tại của giao diện', () => {
  it('hiện trang 404 với nút về trang chủ', () => {
    renderApp('/khong-co-trang-nay/abc');

    expect(screen.getByRole('heading', { name: 'Không tìm thấy trang' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Về trang chủ' })).toHaveAttribute('href', '/');
  });
});
