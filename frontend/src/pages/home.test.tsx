import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { callsTo, created, fail, link, me, renderApp, resetServer, serve } from '../test/helpers';

beforeEach(() => resetServer());
afterEach(() => vi.unstubAllGlobals());

const urlBox = () => screen.getByLabelText('Link cần rút gọn');

describe('trang chủ: rút gọn ẩn danh', () => {
  it('rút gọn rồi hiện link ngắn kèm đích, nút mở link và gợi ý đăng ký', async () => {
    serve({ 'POST /api/v1/public/urls': () => created(link('abc1234')) });
    renderApp('/');

    await userEvent.type(urlBox(), 'https://example.com/abc1234');
    await userEvent.click(screen.getByRole('button', { name: /Rút gọn/ }));

    expect(await screen.findByRole('link', { name: 'sho.rt/abc1234' })).toHaveAttribute('href', 'https://sho.rt/abc1234');
    expect(screen.getByText(/Trỏ tới https:\/\/example.com\/abc1234/)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /Mở link/ })).toHaveAttribute('href', 'https://sho.rt/abc1234');
    expect(screen.getByRole('link', { name: 'Tạo tài khoản miễn phí' })).toHaveAttribute('href', '/register');
    expect(callsTo('POST', '/api/v1/public/urls')[0].body).toEqual({ originalUrl: 'https://example.com/abc1234' });
  });

  it('nút Rút gọn bị khoá khi ô trống', () => {
    renderApp('/');

    expect(screen.getByRole('button', { name: /Rút gọn/ })).toBeDisabled();
  });

  it('tuỳ chọn hạn dùng được gửi kèm với định dạng backend', async () => {
    serve({ 'POST /api/v1/public/urls': () => created(link('abc1234')) });
    renderApp('/');

    await userEvent.type(urlBox(), 'https://example.com/x');
    await userEvent.click(screen.getByRole('button', { name: 'Tuỳ chọn thêm' }));
    await userEvent.type(screen.getByLabelText('Hết hạn lúc'), '2030-05-06T07:08');
    await userEvent.click(screen.getByRole('button', { name: /Rút gọn/ }));

    await screen.findByRole('link', { name: 'sho.rt/abc1234' });
    expect(callsTo('POST', '/api/v1/public/urls')[0].body).toEqual({ originalUrl: 'https://example.com/x', expiresAt: '2030-05-06T07:08:00' });
  });

  it('sao chép link vào clipboard và báo bằng thông báo', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true });
    serve({ 'POST /api/v1/public/urls': () => created(link('abc1234')) });
    renderApp('/');
    await userEvent.type(urlBox(), 'https://example.com/x');
    await userEvent.click(screen.getByRole('button', { name: /Rút gọn/ }));
    await screen.findByRole('link', { name: 'sho.rt/abc1234' });

    await userEvent.click(screen.getByRole('button', { name: 'Sao chép' }));

    expect(writeText).toHaveBeenCalledWith('https://sho.rt/abc1234');
    expect(await screen.findByText('Đã sao chép vào bộ nhớ tạm')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Đã chép' })).toBeInTheDocument();
  });

  it('"Rút gọn link khác" đưa form về trạng thái sạch', async () => {
    serve({ 'POST /api/v1/public/urls': () => created(link('abc1234')) });
    renderApp('/');
    await userEvent.type(urlBox(), 'https://example.com/x');
    await userEvent.click(screen.getByRole('button', { name: /Rút gọn/ }));
    await screen.findByRole('link', { name: 'sho.rt/abc1234' });

    await userEvent.click(screen.getByRole('button', { name: 'Rút gọn link khác' }));

    expect(urlBox()).toHaveValue('');
  });

  it('bị giới hạn tốc độ (429) hiện thời gian chờ', async () => {
    serve({ 'POST /api/v1/public/urls': () => fail(429, 'ERR_429', 'x', null, { 'Retry-After': '30' }) });
    renderApp('/');

    await userEvent.type(urlBox(), 'https://example.com');
    await userEvent.click(screen.getByRole('button', { name: /Rút gọn/ }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Thử lại sau 30 giây');
  });

  it('lỗi theo field của server hiện ngay dưới ô nhập và gắn vào ô bằng aria-describedby', async () => {
    serve({ 'POST /api/v1/public/urls': () => fail(400, 'ERR_001', 'Dữ liệu không hợp lệ', { originalUrl: 'Link không hợp lệ' }) });
    renderApp('/');

    await userEvent.type(urlBox(), 'ftp://x');
    await userEvent.click(screen.getByRole('button', { name: /Rút gọn/ }));

    const error = await screen.findByText('Link không hợp lệ');
    expect(urlBox()).toHaveAttribute('aria-invalid', 'true');
    expect(urlBox().getAttribute('aria-describedby')).toContain(error.id);
  });
});

describe('trang chủ: nội dung giới thiệu', () => {
  it('có đủ phần tính năng, cách hoạt động và hỏi đáp', () => {
    renderApp('/');

    for (const name of ['Thống kê lượt click', 'Mã QR', 'Bảo vệ bằng mật khẩu', 'Alias tuỳ chọn', 'Hẹn ngày hết hạn', 'An toàn từ thiết kế']) {
      expect(screen.getByRole('heading', { name })).toBeInTheDocument();
    }
    expect(screen.getByRole('heading', { name: 'Rút gọn link trong ba bước' })).toBeInTheDocument();
    expect(screen.getByText('Có thực sự miễn phí không?')).toBeInTheDocument();
  });

  it('hỏi đáp mở ra bằng thẻ details và có thể đọc bằng bàn phím', async () => {
    renderApp('/');
    const summary = screen.getByText('Có thực sự miễn phí không?').closest('summary') as HTMLElement;

    await userEvent.click(summary);

    expect(summary.closest('details')).toHaveAttribute('open');
  });

  it('khách thấy lời kêu gọi đăng ký; người đã đăng nhập thì không', async () => {
    const view = renderApp('/');
    expect(screen.getByRole('heading', { name: 'Sẵn sàng làm chủ các link của bạn?' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Đăng nhập' })).toBeInTheDocument();
    view.unmount();

    serve({ 'GET /api/v1/users/me': me });
    renderApp('/', { authed: true });

    // đã đăng nhập: avatar chữ viết tắt thay cho các nút đăng nhập/đăng ký
    expect(await screen.findByRole('button', { name: 'Menu tài khoản' })).toHaveTextContent('AN');
    expect(screen.queryByRole('heading', { name: 'Sẵn sàng làm chủ các link của bạn?' })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Đăng ký miễn phí' })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Đăng nhập' })).not.toBeInTheDocument();
  });

  it('có liên kết bỏ qua điều hướng cho bàn phím', () => {
    renderApp('/');

    expect(screen.getByRole('link', { name: 'Chuyển tới nội dung' })).toHaveAttribute('href', '#main');
  });
});

describe('liên kết # tới các phần của trang', () => {
  it('mở thẳng /#faq thì cuộn tới phần hỏi đáp sau khi trang dựng xong', () => {
    const scrollIntoView = vi.fn();
    Element.prototype.scrollIntoView = scrollIntoView;

    renderApp('/#faq');

    expect(scrollIntoView).toHaveBeenCalledOnce();
    expect(scrollIntoView.mock.contexts[0]).toBe(document.getElementById('faq'));
  });

  it('hash không khớp phần tử nào thì không lỗi và không cuộn', () => {
    const scrollIntoView = vi.fn();
    Element.prototype.scrollIntoView = scrollIntoView;

    renderApp('/#khong-co');

    expect(scrollIntoView).not.toHaveBeenCalled();
  });

  it('không có hash thì không cuộn', () => {
    const scrollIntoView = vi.fn();
    Element.prototype.scrollIntoView = scrollIntoView;

    renderApp('/');

    expect(scrollIntoView).not.toHaveBeenCalled();
  });

  it('các phần có id khớp với liên kết trên thanh điều hướng', () => {
    renderApp('/');

    for (const id of ['features', 'how', 'faq']) {
      expect(document.getElementById(id), id).not.toBeNull();
      expect(document.querySelector('nav a[href="#' + id + '"]'), id).not.toBeNull();
    }
  });
});

describe('ngôn ngữ và giao diện', () => {
  it('đổi sang tiếng Anh và nhớ lựa chọn', async () => {
    renderApp('/');

    await userEvent.click(screen.getByRole('button', { name: 'Ngôn ngữ' }));
    await userEvent.click(screen.getByRole('menuitem', { name: 'English' }));

    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Short links, big results');
    expect(localStorage.getItem('lang')).toBe('en');
  });

  it('đổi giao diện tối: đặt data-theme và nhớ lựa chọn; "theo hệ thống" gỡ thuộc tính', async () => {
    renderApp('/');

    await userEvent.click(screen.getByRole('button', { name: 'Đổi giao diện' }));
    await userEvent.click(screen.getByRole('menuitem', { name: /Tối/ }));
    expect(document.documentElement).toHaveAttribute('data-theme', 'dark');
    expect(localStorage.getItem('theme')).toBe('dark');

    await userEvent.click(screen.getByRole('button', { name: 'Đổi giao diện' }));
    await userEvent.click(screen.getByRole('menuitem', { name: /Theo hệ thống/ }));
    expect(document.documentElement).not.toHaveAttribute('data-theme');
    expect(localStorage.getItem('theme')).toBeNull();
  });

  it('menu đóng bằng phím Esc và trả focus về nút', async () => {
    renderApp('/');
    const trigger = screen.getByRole('button', { name: 'Đổi giao diện' });

    await userEvent.click(trigger);
    expect(screen.getByRole('menu')).toBeInTheDocument();
    await userEvent.keyboard('{Escape}');

    expect(screen.queryByRole('menu')).not.toBeInTheDocument();
    expect(trigger).toHaveFocus();
  });

  it('mũi tên di chuyển giữa các mục menu', async () => {
    renderApp('/');

    await userEvent.click(screen.getByRole('button', { name: 'Đổi giao diện' }));
    const items = within(screen.getByRole('menu')).getAllByRole('menuitem');
    expect(items[0]).toHaveFocus();
    await userEvent.keyboard('{ArrowDown}');
    expect(items[1]).toHaveFocus();
    await userEvent.keyboard('{ArrowUp}{ArrowUp}');
    expect(items[2]).toHaveFocus();
  });

  it('tiêu đề tab theo trang', async () => {
    renderApp('/login');

    expect(document.title).toBe('Đăng nhập · Shortly');
  });
});
