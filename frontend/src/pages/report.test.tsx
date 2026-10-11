import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { AbuseDetail, AbuseSummary } from '../api/types';
import { callsTo, created, fail, me, mockMatchMedia, ok, pageOf, renderApp, resetServer, serve, USER } from '../test/helpers';

beforeEach(() => {
  resetServer();
  mockMatchMedia(() => false);
});
afterEach(() => vi.unstubAllGlobals());

const ADMIN = { ...USER, id: 'admin-1', username: 'root', fullName: 'Root Admin', roles: ['ROLE_USER', 'ROLE_ADMIN'] };
const adminMe = () => ok(ADMIN);
const dialog = () => within(screen.getByRole('dialog'));

// ===========================
//  Trang công khai báo cáo
// ===========================

describe('báo cáo lạm dụng (công khai)', () => {
  const sendOk = { 'POST /api/v1/public/reports': () => created(null) };

  it('mở sẵn với mã từ ?code=, gửi lý do và mô tả, rồi hiện lời cảm ơn', async () => {
    serve(sendOk);
    renderApp('/report?code=abc1234');

    expect(await screen.findByLabelText('Link rút gọn')).toHaveValue('abc1234');
    const submit = screen.getByRole('button', { name: 'Gửi báo cáo' });
    expect(submit).toBeDisabled(); // chưa chọn lý do
    await userEvent.click(screen.getByRole('radio', { name: 'Lừa đảo, giả mạo' }));
    await userEvent.type(screen.getByLabelText('Mô tả thêm'), '  Trang đăng nhập ngân hàng giả  ');
    await userEvent.click(submit);

    expect(await screen.findByText('Cảm ơn bạn đã báo cho chúng tôi')).toBeInTheDocument();
    expect(callsTo('POST', '/api/v1/public/reports')[0].body).toEqual({
      shortCode: 'abc1234',
      reason: 'PHISHING',
      details: 'Trang đăng nhập ngân hàng giả',
    });
    // Không cần đăng nhập: không gửi Authorization
    expect(callsTo('POST', '/api/v1/public/reports')[0].headers.Authorization).toBeUndefined();
  });

  it('dán link rút gọn đầy đủ thì chỉ gửi phần mã, mô tả trống bị bỏ qua', async () => {
    serve(sendOk);
    renderApp('/report');

    await userEvent.type(await screen.findByLabelText('Link rút gọn'), 'https://sho.rt/abc1234');
    await userEvent.click(screen.getByRole('radio', { name: 'Spam' }));
    await userEvent.click(screen.getByRole('button', { name: 'Gửi báo cáo' }));

    await screen.findByText('Cảm ơn bạn đã báo cho chúng tôi');
    expect(callsTo('POST', '/api/v1/public/reports')[0].body).toEqual({ shortCode: 'abc1234', reason: 'SPAM' });
  });

  it('link không đúng định dạng: báo lỗi ngay và không gọi server', async () => {
    serve(sendOk);
    renderApp('/report');

    await userEvent.type(await screen.findByLabelText('Link rút gọn'), 'https://sho.rt/a/b/c');
    await userEvent.click(screen.getByRole('radio', { name: 'Spam' }));
    await userEvent.click(screen.getByRole('button', { name: 'Gửi báo cáo' }));

    expect(await screen.findByText(/Nhập link rút gọn hoặc mã của nó/)).toBeInTheDocument();
    expect(callsTo('POST', '/api/v1/public/reports')).toHaveLength(0);
  });

  it('server báo không tìm thấy link thì hiện lỗi và giữ nguyên form để sửa', async () => {
    serve({ 'POST /api/v1/public/reports': () => fail(404, 'ERR_400', 'Không tìm thấy link') });
    renderApp('/report?code=nope1234');

    await userEvent.click(await screen.findByRole('radio', { name: 'Spam' }));
    await userEvent.click(screen.getByRole('button', { name: 'Gửi báo cáo' }));

    expect(await screen.findByText('Không tìm thấy link')).toBeInTheDocument();
    expect(screen.getByLabelText('Link rút gọn')).toHaveValue('nope1234');
    expect(screen.queryByText('Cảm ơn bạn đã báo cho chúng tôi')).not.toBeInTheDocument();
  });

  it('"Báo cáo link khác" đưa form về trạng thái trống', async () => {
    serve(sendOk);
    renderApp('/report?code=abc1234');
    await userEvent.click(await screen.findByRole('radio', { name: 'Spam' }));
    await userEvent.click(screen.getByRole('button', { name: 'Gửi báo cáo' }));
    await screen.findByText('Cảm ơn bạn đã báo cho chúng tôi');

    await userEvent.click(screen.getByRole('button', { name: 'Báo cáo link khác' }));

    expect(await screen.findByLabelText('Link rút gọn')).toHaveValue('');
    expect(screen.getByRole('radio', { name: 'Spam' })).not.toBeChecked();
  });

  it('chân trang công khai và trang nhập mật khẩu có lối vào báo cáo (trang mật khẩu điền sẵn mã)', async () => {
    serve({});
    renderApp('/unlock/abc1234');

    const links = await screen.findAllByRole('link', { name: 'Báo cáo lạm dụng' });
    expect(links.map((l) => l.getAttribute('href')).sort()).toEqual(['/report', '/report?code=abc1234']);
  });
});

// ===========================
//  Admin xem xét
// ===========================

const summary = (code: string, extra: Partial<AbuseSummary> = {}): AbuseSummary => ({
  shortCode: code,
  shortUrl: `https://sho.rt/${code}`,
  originalUrl: `https://example.com/${code}`,
  linkStatus: 'ACTIVE',
  reportCount: 3,
  lastReportedAt: '2026-10-10T09:00:00',
  reasons: { SPAM: 2, PHISHING: 1 },
  ...extra,
});

const detail = (id: number, extra: Partial<AbuseDetail> = {}): AbuseDetail => ({
  id,
  reason: 'SPAM',
  createdAt: '2026-10-10T09:00:00',
  ...extra,
});

const reportList = (...items: AbuseSummary[]) => () => ok(pageOf(items as never));
const cardOf = (code: string) => screen.getByText(`sho.rt/${code}`).closest('.link-card') as HTMLElement;

describe('quản trị: báo cáo lạm dụng', () => {
  it('chỉ admin thấy mục trong thanh bên; người thường bị đưa về danh sách link của mình', async () => {
    serve({ 'GET /api/v1/users/me': me, 'GET /api/v1/urls': () => ok(pageOf([])) });
    renderApp('/admin/reports', { authed: true });

    await screen.findByText('Tạo link rút gọn đầu tiên của bạn');
    expect(callsTo('GET', '/api/v1/admin/reports')).toHaveLength(0);
  });

  it('liệt kê link bị báo cáo với số báo cáo, lý do và trạng thái; mặc định tab Chờ xử lý', async () => {
    serve({
      'GET /api/v1/users/me': adminMe,
      'GET /api/v1/admin/reports': reportList(summary('abc1234'), summary('bad9999', { linkStatus: 'BLOCKED', reportCount: 1, reasons: { MALWARE: 1 } })),
    });
    renderApp('/admin/reports', { authed: true });

    await screen.findByText('sho.rt/abc1234');
    expect(callsTo('GET', '/api/v1/admin/reports')[0].query.get('status')).toBe('OPEN');
    const card = within(cardOf('abc1234'));
    expect(card.getByText('3 báo cáo')).toBeInTheDocument();
    expect(card.getByText('Spam ×2')).toBeInTheDocument();
    expect(card.getByText('Lừa đảo, giả mạo ×1')).toBeInTheDocument();
    expect(screen.getByText('2 link bị báo cáo')).toBeInTheDocument();
    expect(within(screen.getByRole('navigation', { name: 'Main' })).getByRole('link', { name: 'Báo cáo lạm dụng' })).toHaveAttribute('href', '/admin/reports');
    // Link đã bị chặn thì không còn nút chặn
    expect(within(cardOf('bad9999')).queryByRole('button', { name: /^Chặn link/ })).not.toBeInTheDocument();
    expect(within(cardOf('abc1234')).getByRole('button', { name: 'Chặn link abc1234' })).toBeInTheDocument();
  });

  it('đổi tab gửi status tương ứng; tab đã xử lý không có nút hành động; tab rỗng hiện lời giải thích', async () => {
    serve({
      'GET /api/v1/users/me': adminMe,
      'GET /api/v1/admin/reports': (c) => (c.query.get('status') === 'RESOLVED' ? ok(pageOf([summary('old1234')] as never)) : ok(pageOf([]))),
    });
    renderApp('/admin/reports', { authed: true });
    await screen.findByText('Không có báo cáo nào chờ xử lý');

    await userEvent.click(screen.getByRole('button', { name: 'Đã xử lý' }));
    await screen.findByText('sho.rt/old1234');

    expect(callsTo('GET', '/api/v1/admin/reports').at(-1)!.query.get('status')).toBe('RESOLVED');
    expect(within(cardOf('old1234')).queryByRole('button', { name: /^Đánh dấu đã xử lý/ })).not.toBeInTheDocument();
    expect(within(cardOf('old1234')).queryByRole('button', { name: /^Bỏ qua/ })).not.toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Đã bỏ qua' }));
    expect(await screen.findByText('Chưa có báo cáo nào bị bỏ qua')).toBeInTheDocument();
  });

  it('xem từng báo cáo: tải khi bung thẻ, hiện lý do, mô tả và "Không có mô tả"', async () => {
    serve({
      'GET /api/v1/users/me': adminMe,
      'GET /api/v1/admin/reports': reportList(summary('abc1234')),
      'GET /api/v1/admin/reports/abc1234': () => ok([detail(1, { reason: 'PHISHING', details: 'Giả mạo ngân hàng' }), detail(2)]),
    });
    renderApp('/admin/reports', { authed: true });
    await screen.findByText('sho.rt/abc1234');
    expect(callsTo('GET', '/api/v1/admin/reports/abc1234')).toHaveLength(0); // chưa bung thì chưa tải

    await userEvent.click(within(cardOf('abc1234')).getByRole('button', { name: 'Xem báo cáo' }));

    expect(await screen.findByText('Giả mạo ngân hàng')).toBeInTheDocument();
    expect(screen.getByText('Không có mô tả')).toBeInTheDocument();
    expect(callsTo('GET', '/api/v1/admin/reports/abc1234')[0].query.get('status')).toBe('OPEN');
    await userEvent.click(screen.getByRole('button', { name: 'Ẩn báo cáo' }));
    expect(screen.queryByText('Giả mạo ngân hàng')).not.toBeInTheDocument();
  });

  it('đánh dấu đã xử lý và bỏ qua: gọi đúng endpoint, báo toast và tải lại danh sách', async () => {
    let listed = 0;
    serve({
      'GET /api/v1/users/me': adminMe,
      'GET /api/v1/admin/reports': () => {
        listed++;
        return ok(pageOf((listed === 1 ? [summary('abc1234'), summary('def5678')] : [summary('def5678')]) as never));
      },
      'PUT /api/v1/admin/reports/abc1234/resolve': () => ok(3),
      'PUT /api/v1/admin/reports/def5678/dismiss': () => ok(1),
    });
    renderApp('/admin/reports', { authed: true });
    await screen.findByText('sho.rt/abc1234');

    await userEvent.click(screen.getByRole('button', { name: 'Đánh dấu đã xử lý abc1234' }));

    expect(await screen.findByText('Đã đánh dấu các báo cáo là đã xử lý')).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByText('sho.rt/abc1234')).not.toBeInTheDocument());
    expect(callsTo('PUT', '/api/v1/admin/reports/abc1234/resolve')).toHaveLength(1);

    await userEvent.click(screen.getByRole('button', { name: 'Bỏ qua def5678' }));
    expect(await screen.findByText('Đã bỏ qua các báo cáo')).toBeInTheDocument();
    expect(callsTo('PUT', '/api/v1/admin/reports/def5678/dismiss')).toHaveLength(1);
  });

  it('chặn link từ báo cáo: bắt buộc lý do, gửi PUT block rồi tải lại', async () => {
    let listed = 0;
    serve({
      'GET /api/v1/users/me': adminMe,
      'GET /api/v1/admin/reports': () => {
        listed++;
        return ok(pageOf((listed === 1 ? [summary('abc1234')] : []) as never));
      },
      'PUT /api/v1/admin/urls/abc1234/block': () => ok({ shortCode: 'abc1234', shortUrl: 'https://sho.rt/abc1234', status: 'BLOCKED' }),
    });
    renderApp('/admin/reports', { authed: true });
    await screen.findByText('sho.rt/abc1234');

    await userEvent.click(screen.getByRole('button', { name: 'Chặn link abc1234' }));
    expect(dialog().getByRole('button', { name: 'Chặn link' })).toBeDisabled();
    await userEvent.type(dialog().getByLabelText('Lý do'), 'Lừa đảo');
    await userEvent.click(dialog().getByRole('button', { name: 'Chặn link' }));

    expect(await screen.findByText('Không có báo cáo nào chờ xử lý')).toBeInTheDocument();
    expect(callsTo('PUT', '/api/v1/admin/urls/abc1234/block')[0].body).toEqual({ reason: 'Lừa đảo' });
  });

  it('lỗi tải danh sách hiện thông báo và nút thử lại', async () => {
    let attempts = 0;
    serve({
      'GET /api/v1/users/me': adminMe,
      'GET /api/v1/admin/reports': () => (++attempts === 1 ? fail(500, 'ERR_999', 'Lỗi máy chủ') : ok(pageOf([summary('abc1234')] as never))),
    });
    renderApp('/admin/reports', { authed: true });

    expect(await screen.findByRole('alert')).toHaveTextContent('Lỗi máy chủ');
    await userEvent.click(screen.getByRole('button', { name: 'Thử lại' }));
    expect(await screen.findByText('sho.rt/abc1234')).toBeInTheDocument();
  });
});
