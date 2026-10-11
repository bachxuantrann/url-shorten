import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { callsTo, created, fail, fetchMock, link, listCalls, me, mockMatchMedia, ok, pageOf, renderApp, resetServer, serve } from '../test/helpers';

beforeEach(() => resetServer());
afterEach(() => vi.unstubAllGlobals());

const rowOf = (code: string) => screen.getByRole('link', { name: `sho.rt/${code}` }).closest('tr') as HTMLElement;
const menuOf = (code: string) => within(rowOf(code)).getByRole('button', { name: 'Thêm thao tác' });
const dialog = () => within(screen.getByRole('dialog'));
const listWith = (...links: ReturnType<typeof link>[]) => () => ok(pageOf(links));

async function openMenu(code: string, item: string) {
  await userEvent.click(menuOf(code));
  await userEvent.click(screen.getByRole('menuitem', { name: item }));
}

describe('danh sách link', () => {
  it('hiện link với trạng thái, lượt click, hạn dùng, ngày tạo và nhãn mật khẩu', async () => {
    serve({
      'GET /api/v1/users/me': me,
      'GET /api/v1/urls': listWith(
        link('abc1234', { clickCount: 1234, passwordProtected: true }),
        link('old9999', { status: 'EXPIRED', expiresAt: '2026-09-01T08:00:00' }),
      ),
    });
    renderApp('/dashboard', { authed: true });

    await screen.findByRole('link', { name: 'sho.rt/abc1234' });
    const r1 = within(rowOf('abc1234'));
    expect(r1.getByText('Đang hoạt động')).toBeInTheDocument();
    expect(r1.getByText('Mật khẩu')).toBeInTheDocument();
    expect(r1.getByText('Không hết hạn')).toBeInTheDocument();
    expect(r1.getByText(/^1[.,]234$/)).toBeInTheDocument();
    expect(r1.getByText('https://example.com/abc1234')).toBeInTheDocument();
    const r2 = within(rowOf('old9999'));
    expect(r2.getByText('Hết hạn', { selector: '.badge' })).toBeInTheDocument();
    expect(r2.getByText(/Hết hạn .*2026/)).toBeInTheDocument();
    expect(screen.getByText('2 link')).toBeInTheDocument();
  });

  it('tên link dẫn tới trang thống kê; nút mở link mở tab mới với rel an toàn', async () => {
    serve({ 'GET /api/v1/users/me': me, 'GET /api/v1/urls': listWith(link('abc1234')) });
    renderApp('/dashboard', { authed: true });

    const name = await screen.findByRole('link', { name: 'sho.rt/abc1234' });
    const open = within(rowOf('abc1234')).getByRole('link', { name: 'Mở link rút gọn' });

    expect(name).toHaveAttribute('href', '/links/abc1234');
    expect(open).toHaveAttribute('href', 'https://sho.rt/abc1234');
    expect(open).toHaveAttribute('target', '_blank');
    expect(open).toHaveAttribute('rel', expect.stringContaining('noopener'));
  });

  it('đang tải hiện khung chờ (skeleton) thay vì trang trắng', async () => {
    let release!: () => void;
    const gate = new Promise<void>((resolve) => (release = resolve));
    // Giữ phản hồi danh sách lại cho tới khi test cho phép, để quan sát trạng thái đang tải
    fetchMock.mockImplementation(async (url: string) => {
      if (String(url).startsWith('/api/v1/users/me')) return me();
      await gate;
      return ok(pageOf([link('abc1234')]));
    });
    renderApp('/dashboard', { authed: true });

    expect(await screen.findByLabelText('Đang tải...')).toBeInTheDocument();
    release();
    expect(await screen.findByRole('link', { name: 'sho.rt/abc1234' })).toBeInTheDocument();
    expect(screen.queryByLabelText('Đang tải...')).not.toBeInTheDocument();
  });

  it('chưa có link nào: hiện trạng thái rỗng có nút tạo link mở hộp thoại', async () => {
    serve({ 'GET /api/v1/users/me': me, 'GET /api/v1/urls': listWith() });
    renderApp('/dashboard', { authed: true });

    expect(await screen.findByText('Tạo link rút gọn đầu tiên của bạn')).toBeInTheDocument();
    const cta = screen.getAllByRole('button', { name: 'Tạo link' }).find((b) => b.closest('.empty'))!;
    await userEvent.click(cta);

    expect(dialog().getByRole('heading', { name: 'Tạo link rút gọn' })).toBeInTheDocument();
  });

  it('tìm kiếm gửi keyword sau khi ngừng gõ (một request, không phải mỗi phím)', async () => {
    serve({ 'GET /api/v1/users/me': me, 'GET /api/v1/urls': listWith(link('abc1234')) });
    renderApp('/dashboard', { authed: true });
    await screen.findByRole('link', { name: 'sho.rt/abc1234' });
    const before = listCalls().length;

    await userEvent.type(screen.getByRole('searchbox'), 'github');

    await waitFor(() => expect(listCalls().at(-1)?.query.get('keyword')).toBe('github'));
    expect(listCalls().length - before).toBe(1);
  });

  it('xoá ô tìm kiếm bằng nút X', async () => {
    serve({ 'GET /api/v1/users/me': me, 'GET /api/v1/urls': listWith(link('abc1234')) });
    renderApp('/dashboard', { authed: true });
    await screen.findByRole('link', { name: 'sho.rt/abc1234' });
    await userEvent.type(screen.getByRole('searchbox'), 'git');

    await userEvent.click(screen.getByRole('button', { name: 'Xoá tìm kiếm' }));

    expect(screen.getByRole('searchbox')).toHaveValue('');
    expect(screen.queryByRole('button', { name: 'Xoá tìm kiếm' })).not.toBeInTheDocument();
  });

  it('lọc theo trạng thái và sắp xếp gửi đúng tham số, về trang đầu, và nút lọc báo trạng thái đã chọn', async () => {
    serve({ 'GET /api/v1/users/me': me, 'GET /api/v1/urls': listWith(link('abc1234')) });
    renderApp('/dashboard', { authed: true });
    await screen.findByRole('link', { name: 'sho.rt/abc1234' });

    await userEvent.click(screen.getByRole('button', { name: 'Đã tắt' }));
    await userEvent.click(screen.getByRole('button', { name: /Sắp xếp/ }));
    await userEvent.click(screen.getByRole('option', { name: 'Nhiều click nhất' }));

    await waitFor(() => {
      const last = listCalls().at(-1)!;
      expect(last.query.get('status')).toBe('DISABLED');
      expect(last.query.get('sortBy')).toBe('clickCount');
      expect(last.query.get('page')).toBe('0');
    });
    expect(screen.getByRole('button', { name: 'Đã tắt' })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('button', { name: 'Tất cả' })).toHaveAttribute('aria-pressed', 'false');
  });

  it('bộ lọc không có kết quả: hiện thông báo riêng và nút xoá bộ lọc', async () => {
    serve({
      'GET /api/v1/users/me': me,
      'GET /api/v1/urls': (c) => ok(pageOf(c.query.get('status') === 'BLOCKED' ? [] : [link('abc1234')])),
    });
    renderApp('/dashboard', { authed: true });
    await screen.findByRole('link', { name: 'sho.rt/abc1234' });

    await userEvent.click(screen.getByRole('button', { name: 'Bị chặn' }));
    expect(await screen.findByText('Không có link nào khớp bộ lọc')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Xoá bộ lọc' }));

    expect(await screen.findByRole('link', { name: 'sho.rt/abc1234' })).toBeInTheDocument();
  });

  it('phân trang: hiện khoảng đang xem và gọi đúng trang kế tiếp', async () => {
    serve({
      'GET /api/v1/users/me': me,
      'GET /api/v1/urls': (c) =>
        c.query.get('page') === '1'
          ? ok(pageOf([link('page2aa')], { page: 1, totalPages: 2, totalElements: 11, hasPrevious: true }))
          : ok(pageOf([link('page1aa')], { totalPages: 2, totalElements: 11, hasNext: true })),
    });
    renderApp('/dashboard', { authed: true });
    expect(await screen.findByText('Hiển thị 1-1 trên 11')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Sau' }));

    expect(await screen.findByRole('link', { name: 'sho.rt/page2aa' })).toBeInTheDocument();
    expect(screen.getByText('Hiển thị 11-11 trên 11')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Sau' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Trước' })).toBeEnabled();
  });

  it('lỗi tải danh sách hiện thông báo và nút thử lại hoạt động', async () => {
    let attempts = 0;
    serve({
      'GET /api/v1/users/me': me,
      'GET /api/v1/urls': () => (++attempts === 1 ? fail(500, 'ERR_500', 'Lỗi hệ thống') : ok(pageOf([link('abc1234')]))),
    });
    renderApp('/dashboard', { authed: true });

    expect(await screen.findByRole('alert')).toHaveTextContent('Lỗi hệ thống');
    await userEvent.click(screen.getByRole('button', { name: 'Thử lại' }));

    expect(await screen.findByRole('link', { name: 'sho.rt/abc1234' })).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('màn hình hẹp dùng thẻ thay vì bảng, vẫn đủ thông tin và thao tác', async () => {
    mockMatchMedia(() => true);
    serve({ 'GET /api/v1/users/me': me, 'GET /api/v1/urls': listWith(link('abc1234', { clickCount: 7 })) });
    renderApp('/dashboard', { authed: true });

    await screen.findByRole('link', { name: 'sho.rt/abc1234' });

    expect(screen.queryByRole('table')).not.toBeInTheDocument();
    expect(screen.getByText('7 lượt click')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Thống kê' })).toHaveAttribute('href', '/links/abc1234');
    expect(screen.getByRole('button', { name: 'Thêm thao tác' })).toBeInTheDocument();
  });

  it('link bị chặn hiện lý do và chỉ còn thống kê/QR trong menu (không sửa, tắt, xoá)', async () => {
    serve({
      'GET /api/v1/users/me': me,
      'GET /api/v1/urls': listWith(link('bad0000', { status: 'BLOCKED', blockedReason: 'phishing' })),
    });
    renderApp('/dashboard', { authed: true });
    await screen.findByRole('link', { name: 'sho.rt/bad0000' });

    expect(within(rowOf('bad0000')).getByText(/Quản trị viên đã chặn: phishing/)).toBeInTheDocument();
    await userEvent.click(menuOf('bad0000'));

    expect(screen.getByRole('menuitem', { name: 'Thống kê' })).toBeInTheDocument();
    expect(screen.getByRole('menuitem', { name: 'Mã QR' })).toBeInTheDocument();
    for (const name of ['Sửa đích', 'Mật khẩu', 'Tắt', 'Bật', 'Xoá']) {
      expect(screen.queryByRole('menuitem', { name })).not.toBeInTheDocument();
    }
  });
});

describe('tạo link', () => {
  const topCreate = () => screen.getAllByRole('button', { name: 'Tạo link' })[0];

  it('gửi alias, mật khẩu và hạn dùng, báo thành công, đóng hộp thoại và tải lại danh sách', async () => {
    let made = false;
    serve({
      'GET /api/v1/users/me': me,
      'GET /api/v1/urls': () => ok(pageOf(made ? [link('my-alias')] : [])),
      'POST /api/v1/urls': () => {
        made = true;
        return created(link('my-alias'));
      },
    });
    renderApp('/dashboard', { authed: true });
    await screen.findByText('Tạo link rút gọn đầu tiên của bạn');

    await userEvent.click(topCreate());
    await userEvent.type(dialog().getByLabelText('URL đích'), 'https://example.com/target');
    await userEvent.click(dialog().getByRole('button', { name: 'Tuỳ chọn nâng cao' }));
    await userEvent.type(dialog().getByLabelText('Alias tuỳ chọn'), 'my-alias');
    await userEvent.type(dialog().getByLabelText('Bảo vệ bằng mật khẩu'), 'secret1');
    await userEvent.type(dialog().getByLabelText('Hết hạn lúc'), '2030-01-02T03:04');
    await userEvent.click(dialog().getByRole('button', { name: 'Tạo link' }));

    expect(await screen.findByRole('link', { name: 'sho.rt/my-alias' })).toBeInTheDocument();
    expect(await screen.findByText('Đã tạo link')).toBeInTheDocument();
    expect(callsTo('POST', '/api/v1/urls')[0].body).toEqual({
      originalUrl: 'https://example.com/target',
      alias: 'my-alias',
      password: 'secret1',
      expiresAt: '2030-01-02T03:04:00',
    });
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('chỉ nhập URL: các tuỳ chọn nâng cao không bị gửi', async () => {
    serve({
      'GET /api/v1/users/me': me,
      'GET /api/v1/urls': listWith(),
      'POST /api/v1/urls': () => created(link('abc1234')),
    });
    renderApp('/dashboard', { authed: true });
    await screen.findByText('Tạo link rút gọn đầu tiên của bạn');

    await userEvent.click(topCreate());
    await userEvent.type(dialog().getByLabelText('URL đích'), 'https://example.com/only');
    await userEvent.click(dialog().getByRole('button', { name: 'Tạo link' }));

    await waitFor(() => expect(callsTo('POST', '/api/v1/urls')).toHaveLength(1));
    expect(callsTo('POST', '/api/v1/urls')[0].body).toEqual({ originalUrl: 'https://example.com/only' });
  });

  it('nút tạo bị khoá khi chưa có URL', async () => {
    serve({ 'GET /api/v1/users/me': me, 'GET /api/v1/urls': listWith() });
    renderApp('/dashboard', { authed: true });
    await screen.findByText('Tạo link rút gọn đầu tiên của bạn');

    await userEvent.click(topCreate());

    expect(dialog().getByRole('button', { name: 'Tạo link' })).toBeDisabled();
  });

  it('alias đã bị lấy (409) hiện lỗi, giữ hộp thoại mở và giữ nội dung đã nhập', async () => {
    serve({
      'GET /api/v1/users/me': me,
      'GET /api/v1/urls': listWith(),
      'POST /api/v1/urls': () => fail(409, 'ERR_407', 'Alias không khả dụng'),
    });
    renderApp('/dashboard', { authed: true });
    await screen.findByText('Tạo link rút gọn đầu tiên của bạn');

    await userEvent.click(topCreate());
    await userEvent.type(dialog().getByLabelText('URL đích'), 'https://example.com/x');
    await userEvent.click(dialog().getByRole('button', { name: 'Tuỳ chọn nâng cao' }));
    await userEvent.type(dialog().getByLabelText('Alias tuỳ chọn'), 'taken');
    await userEvent.click(dialog().getByRole('button', { name: 'Tạo link' }));

    expect(await dialog().findByText('Alias không khả dụng')).toBeInTheDocument();
    expect(dialog().getByLabelText('Alias tuỳ chọn')).toHaveValue('taken');
    expect(dialog().getByLabelText('URL đích')).toHaveValue('https://example.com/x');
  });

  it('lỗi validation theo field hiện dưới đúng ô', async () => {
    serve({
      'GET /api/v1/users/me': me,
      'GET /api/v1/urls': listWith(),
      'POST /api/v1/urls': () => fail(400, 'ERR_001', 'Dữ liệu không hợp lệ', { originalUrl: 'Link không hợp lệ' }),
    });
    renderApp('/dashboard', { authed: true });
    await screen.findByText('Tạo link rút gọn đầu tiên của bạn');

    await userEvent.click(topCreate());
    await userEvent.type(dialog().getByLabelText('URL đích'), 'ftp://x');
    await userEvent.click(dialog().getByRole('button', { name: 'Tạo link' }));

    expect(await dialog().findByText('Link không hợp lệ')).toBeInTheDocument();
    expect(dialog().getByLabelText('URL đích')).toHaveAttribute('aria-invalid', 'true');
  });

  it('Huỷ đóng hộp thoại và lần mở sau bắt đầu từ form sạch', async () => {
    serve({ 'GET /api/v1/users/me': me, 'GET /api/v1/urls': listWith() });
    renderApp('/dashboard', { authed: true });
    await screen.findByText('Tạo link rút gọn đầu tiên của bạn');
    await userEvent.click(topCreate());
    await userEvent.type(dialog().getByLabelText('URL đích'), 'https://example.com/draft');

    await userEvent.click(dialog().getByRole('button', { name: 'Huỷ' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    await userEvent.click(topCreate());

    expect(dialog().getByLabelText('URL đích')).toHaveValue('');
  });
});

describe('thao tác trên link', () => {
  it('tắt link cập nhật trạng thái ngay tại dòng, báo bằng thông báo, không tải lại cả danh sách', async () => {
    serve({
      'GET /api/v1/users/me': me,
      'GET /api/v1/urls': listWith(link('abc1234')),
      'PUT /api/v1/urls/abc1234/disable': () => ok(link('abc1234', { status: 'DISABLED' })),
    });
    renderApp('/dashboard', { authed: true });
    await screen.findByRole('link', { name: 'sho.rt/abc1234' });
    const before = listCalls().length;

    await openMenu('abc1234', 'Tắt');

    expect(await within(rowOf('abc1234')).findByText('Đã tắt')).toBeInTheDocument();
    expect(await screen.findByText('Đã tắt link')).toBeInTheDocument();
    expect(listCalls().length).toBe(before);
    await userEvent.click(menuOf('abc1234'));
    expect(screen.getByRole('menuitem', { name: 'Bật' })).toBeInTheDocument();
  });

  it('bật lại link đã tắt', async () => {
    serve({
      'GET /api/v1/users/me': me,
      'GET /api/v1/urls': listWith(link('abc1234', { status: 'DISABLED' })),
      'PUT /api/v1/urls/abc1234/enable': () => ok(link('abc1234')),
    });
    renderApp('/dashboard', { authed: true });
    await screen.findByRole('link', { name: 'sho.rt/abc1234' });

    await openMenu('abc1234', 'Bật');

    expect(await within(rowOf('abc1234')).findByText('Đang hoạt động')).toBeInTheDocument();
    expect(await screen.findByText('Đã bật link')).toBeInTheDocument();
  });

  it('thao tác tắt thất bại hiện thông báo lỗi và giữ nguyên trạng thái', async () => {
    serve({
      'GET /api/v1/users/me': me,
      'GET /api/v1/urls': listWith(link('abc1234')),
      'PUT /api/v1/urls/abc1234/disable': () => fail(403, 'ERR_406', 'Link đã bị chặn'),
    });
    renderApp('/dashboard', { authed: true });
    await screen.findByRole('link', { name: 'sho.rt/abc1234' });

    await openMenu('abc1234', 'Tắt');

    expect(await screen.findByText('Link đã bị chặn')).toBeInTheDocument();
    expect(within(rowOf('abc1234')).getByText('Đang hoạt động')).toBeInTheDocument();
  });

  it('sửa đích điền sẵn giá trị hiện tại, gửi đích mới và bỏ hạn dùng khi để trống', async () => {
    serve({
      'GET /api/v1/users/me': me,
      'GET /api/v1/urls': listWith(link('abc1234', { status: 'EXPIRED', expiresAt: '2026-09-01T08:00:00' })),
      'PUT /api/v1/urls/abc1234': () => ok(link('abc1234', { originalUrl: 'https://new.example.com' })),
    });
    renderApp('/dashboard', { authed: true });
    await screen.findByRole('link', { name: 'sho.rt/abc1234' });

    await openMenu('abc1234', 'Sửa đích');
    const url = dialog().getByLabelText('URL đích');
    expect(url).toHaveValue('https://example.com/abc1234');
    expect(dialog().getByLabelText('Hết hạn lúc')).toHaveValue('2026-09-01T08:00');
    await userEvent.clear(url);
    await userEvent.type(url, 'https://new.example.com');
    await userEvent.click(dialog().getByRole('button', { name: 'Bỏ hạn dùng' }));
    await userEvent.click(dialog().getByRole('button', { name: 'Lưu thay đổi' }));

    expect(await within(rowOf('abc1234')).findByText('https://new.example.com')).toBeInTheDocument();
    expect(callsTo('PUT', '/api/v1/urls/abc1234')[0].body).toEqual({ originalUrl: 'https://new.example.com' });
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(await screen.findByText('Đã cập nhật link')).toBeInTheDocument();
  });

  it('nút Lưu của hộp thoại sửa bị khoá khi chưa đổi gì, mở khi có thay đổi và khoá lại nếu đổi về như cũ', async () => {
    serve({
      'GET /api/v1/users/me': me,
      'GET /api/v1/urls': listWith(link('abc1234', { expiresAt: '2030-01-01T08:00:00' })),
      'PUT /api/v1/urls/abc1234': () => ok(link('abc1234')),
    });
    renderApp('/dashboard', { authed: true });
    await screen.findByRole('link', { name: 'sho.rt/abc1234' });

    await openMenu('abc1234', 'Sửa đích');
    const save = dialog().getByRole('button', { name: 'Lưu thay đổi' });
    expect(save).toBeDisabled();

    // Đổi đích -> mở; đổi về như cũ -> khoá lại
    await userEvent.type(dialog().getByLabelText('URL đích'), 'x');
    expect(save).toBeEnabled();
    await userEvent.type(dialog().getByLabelText('URL đích'), '{Backspace}');
    expect(save).toBeDisabled();

    // Đổi hạn dùng -> mở; bỏ hạn dùng cũng là một thay đổi
    fireEvent.change(dialog().getByLabelText('Hết hạn lúc'), { target: { value: '2031-02-02T09:30' } });
    expect(save).toBeEnabled();
    fireEvent.change(dialog().getByLabelText('Hết hạn lúc'), { target: { value: '2030-01-01T08:00' } });
    expect(save).toBeDisabled();
    await userEvent.click(dialog().getByRole('button', { name: 'Bỏ hạn dùng' }));
    expect(save).toBeEnabled();

    // Khoảng trắng thừa quanh đích không phải là thay đổi
    await userEvent.click(dialog().getByRole('button', { name: 'Huỷ' }));
    await openMenu('abc1234', 'Sửa đích');
    await userEvent.type(dialog().getByLabelText('URL đích'), '   ');
    expect(dialog().getByRole('button', { name: 'Lưu thay đổi' })).toBeDisabled();
    expect(callsTo('PUT', '/api/v1/urls/abc1234')).toHaveLength(0);
  });

  it('lỗi từ server khi sửa hiện trong hộp thoại và giữ nó mở', async () => {
    serve({
      'GET /api/v1/users/me': me,
      'GET /api/v1/urls': listWith(link('abc1234')),
      'PUT /api/v1/urls/abc1234': () => fail(400, 'ERR_300', 'Link đích không an toàn'),
    });
    renderApp('/dashboard', { authed: true });
    await screen.findByRole('link', { name: 'sho.rt/abc1234' });

    await openMenu('abc1234', 'Sửa đích');
    await userEvent.type(dialog().getByLabelText('URL đích'), '/changed');
    await userEvent.click(dialog().getByRole('button', { name: 'Lưu thay đổi' }));

    expect(await dialog().findByText('Link đích không an toàn')).toBeInTheDocument();
    expect(dialog().getByLabelText('URL đích')).toBeInTheDocument();
  });

  it('đặt mật khẩu rồi gỡ mật khẩu', async () => {
    serve({
      'GET /api/v1/users/me': me,
      'GET /api/v1/urls': listWith(link('abc1234')),
      'PUT /api/v1/urls/abc1234/password': () => ok(link('abc1234', { passwordProtected: true })),
      'DELETE /api/v1/urls/abc1234/password': () => ok(link('abc1234', { passwordProtected: false })),
    });
    renderApp('/dashboard', { authed: true });
    await screen.findByRole('link', { name: 'sho.rt/abc1234' });

    await openMenu('abc1234', 'Mật khẩu');
    expect(dialog().queryByRole('button', { name: 'Gỡ mật khẩu' })).not.toBeInTheDocument();
    await userEvent.type(dialog().getByLabelText('Mật khẩu mới'), 'newpass');
    await userEvent.click(dialog().getByRole('button', { name: 'Đặt mật khẩu' }));
    expect(await within(rowOf('abc1234')).findByText('Mật khẩu')).toBeInTheDocument();
    expect(callsTo('PUT', '/api/v1/urls/abc1234/password')[0].body).toEqual({ password: 'newpass' });
    expect(await screen.findByText('Đã bật bảo vệ bằng mật khẩu')).toBeInTheDocument();

    await openMenu('abc1234', 'Mật khẩu');
    await userEvent.click(dialog().getByRole('button', { name: 'Gỡ mật khẩu' }));

    await waitFor(() => expect(within(rowOf('abc1234')).queryByText('Mật khẩu')).not.toBeInTheDocument());
    expect(await screen.findByText('Đã gỡ mật khẩu')).toBeInTheDocument();
  });

  it('xoá cần xác nhận và nêu rõ link nào; huỷ thì không gọi API', async () => {
    serve({ 'GET /api/v1/users/me': me, 'GET /api/v1/urls': listWith(link('abc1234')) });
    renderApp('/dashboard', { authed: true });
    await screen.findByRole('link', { name: 'sho.rt/abc1234' });

    await openMenu('abc1234', 'Xoá');
    expect(dialog().getByText(/https:\/\/sho\.rt\/abc1234/)).toBeInTheDocument();
    expect(dialog().getByText(/không bao giờ được dùng lại/)).toBeInTheDocument();
    await userEvent.click(dialog().getByRole('button', { name: 'Huỷ' }));

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(callsTo('DELETE', '/api/v1/urls/abc1234')).toHaveLength(0);
  });

  it('xoá xác nhận gọi DELETE, báo thành công rồi tải lại danh sách', async () => {
    let deleted = false;
    serve({
      'GET /api/v1/users/me': me,
      'GET /api/v1/urls': () => ok(pageOf(deleted ? [] : [link('abc1234')])),
      'DELETE /api/v1/urls/abc1234': () => {
        deleted = true;
        return ok(null);
      },
    });
    renderApp('/dashboard', { authed: true });
    await screen.findByRole('link', { name: 'sho.rt/abc1234' });

    await openMenu('abc1234', 'Xoá');
    await userEvent.click(dialog().getByRole('button', { name: 'Xoá link' }));

    expect(await screen.findByText('Tạo link rút gọn đầu tiên của bạn')).toBeInTheDocument();
    expect(await screen.findByText('Đã xoá link')).toBeInTheDocument();
  });

  it('xoá link cuối của trang cuối thì lùi về trang trước', async () => {
    let deleted = false;
    serve({
      'GET /api/v1/users/me': me,
      'GET /api/v1/urls': (c) => {
        if (c.query.get('page') === '1') {
          return deleted
            ? ok(pageOf([], { page: 1, totalPages: 1, totalElements: 10 }))
            : ok(pageOf([link('last000')], { page: 1, totalPages: 2, hasPrevious: true, totalElements: 11 }));
        }
        return ok(pageOf([link('first00')], { totalPages: deleted ? 1 : 2, hasNext: !deleted }));
      },
      'DELETE /api/v1/urls/last000': () => {
        deleted = true;
        return ok(null);
      },
    });
    renderApp('/dashboard', { authed: true });
    await screen.findByRole('link', { name: 'sho.rt/first00' });
    await userEvent.click(screen.getByRole('button', { name: 'Sau' }));
    await screen.findByRole('link', { name: 'sho.rt/last000' });

    await openMenu('last000', 'Xoá');
    await userEvent.click(dialog().getByRole('button', { name: 'Xoá link' }));

    expect(await screen.findByRole('link', { name: 'sho.rt/first00' })).toBeInTheDocument();
  });

  it('mã QR tải bằng token, hiện trong hộp thoại và cho tải PNG; giải phóng object URL khi đóng', async () => {
    URL.createObjectURL = vi.fn(() => 'blob:qr-1');
    URL.revokeObjectURL = vi.fn();
    serve({
      'GET /api/v1/users/me': me,
      'GET /api/v1/urls': listWith(link('abc1234')),
      'GET /api/v1/urls/abc1234/qr': () => new Response(new Uint8Array([137, 80, 78, 71]), { status: 200, headers: { 'Content-Type': 'image/png' } }),
    });
    renderApp('/dashboard', { authed: true });
    await screen.findByRole('link', { name: 'sho.rt/abc1234' });

    await openMenu('abc1234', 'Mã QR');

    const img = await dialog().findByAltText('Mã QR của https://sho.rt/abc1234');
    expect(img).toHaveAttribute('src', 'blob:qr-1');
    expect(dialog().getByRole('link', { name: 'Tải PNG' })).toHaveAttribute('download', 'qr-abc1234.png');
    const qr = callsTo('GET', '/api/v1/urls/abc1234/qr')[0];
    expect(qr.headers.Authorization).toBe('Bearer at');
    expect(qr.query.get('size')).toBe('480');
    // Có hai nút "Đóng" (dấu X ở tiêu đề và nút ở chân hộp thoại): bấm nút ở chân
    await userEvent.click(dialog().getAllByRole('button', { name: 'Đóng' }).at(-1)!);
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:qr-1');
  });

  it('nút sao chép trong dòng chép đúng link rút gọn', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true });
    serve({ 'GET /api/v1/users/me': me, 'GET /api/v1/urls': listWith(link('abc1234')) });
    renderApp('/dashboard', { authed: true });
    await screen.findByRole('link', { name: 'sho.rt/abc1234' });

    await userEvent.click(within(rowOf('abc1234')).getByRole('button', { name: 'Sao chép' }));

    expect(writeText).toHaveBeenCalledWith('https://sho.rt/abc1234');
  });
});
