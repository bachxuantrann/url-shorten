import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { cloneElement, type ReactElement } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { AdminAppeal, AppealInfo, UrlStats } from '../api/types';
import { callsTo, created, fail, link, me, mockMatchMedia, ok, pageOf, renderApp, resetServer, serve, USER } from '../test/helpers';

// jsdom không có layout nên ResponsiveContainer đo ra 0x0: cho kích thước cố định (chỉ trang chi tiết cần)
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

const appealInfo = (extra: Partial<AppealInfo> = {}): AppealInfo => ({
  status: 'OPEN',
  message: 'Đây là trang của tôi',
  createdAt: '2026-10-10T09:00:00',
  ...extra,
});
const blocked = (code: string, appeal?: AppealInfo) => link(code, { status: 'BLOCKED', blockedReason: 'phishing', appeal });

const rowOf = (code: string) => screen.getByRole('link', { name: `sho.rt/${code}` }).closest('tr') as HTMLElement;
const listWith = (...links: ReturnType<typeof link>[]) => () => ok(pageOf(links));

// ===========================
//  Phía chủ link
// ===========================

describe('kháng nghị link bị chặn (chủ link)', () => {
  it('link bị chặn chưa kháng nghị có nút Kháng nghị; gửi nội dung đã cắt khoảng trắng rồi hiện "đang chờ"', async () => {
    let sent = false;
    serve({
      'GET /api/v1/users/me': me,
      'GET /api/v1/urls': () => ok(pageOf([sent ? blocked('bad0000', appealInfo()) : blocked('bad0000')])),
      'POST /api/v1/urls/bad0000/appeal': () => {
        sent = true;
        return created(blocked('bad0000', appealInfo()));
      },
    });
    renderApp('/dashboard', { authed: true });
    await screen.findByRole('link', { name: 'sho.rt/bad0000' });

    await userEvent.click(within(rowOf('bad0000')).getByRole('button', { name: 'Kháng nghị' }));
    const submit = dialog().getByRole('button', { name: 'Gửi kháng nghị' });
    expect(submit).toBeDisabled(); // chưa viết gì
    expect(dialog().getByText(/Lý do bị chặn: phishing/)).toBeInTheDocument();
    await userEvent.type(dialog().getByLabelText('Nội dung kháng nghị'), '  Đây là trang của tôi  ');
    await userEvent.click(submit);

    expect(await within(rowOf('bad0000')).findByText('Đã gửi kháng nghị, đang chờ xem xét')).toBeInTheDocument();
    expect(callsTo('POST', '/api/v1/urls/bad0000/appeal')[0].body).toEqual({ message: 'Đây là trang của tôi' });
    expect(screen.getByText('Đã gửi kháng nghị')).toBeInTheDocument(); // toast
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    // Đang chờ thì không gửi thêm được: không còn nút trong hàng và không còn mục trong menu
    expect(within(rowOf('bad0000')).queryByRole('button', { name: 'Kháng nghị' })).not.toBeInTheDocument();
    await userEvent.click(within(rowOf('bad0000')).getByRole('button', { name: 'Thêm thao tác' }));
    expect(screen.queryByRole('menuitem', { name: /Kháng nghị/ })).not.toBeInTheDocument();
  });

  it('kháng nghị bị từ chối hiện lời phản hồi và cho kháng nghị lại (cả ở nút lẫn trong menu)', async () => {
    serve({
      'GET /api/v1/users/me': me,
      'GET /api/v1/urls': listWith(
        blocked('bad0000', appealInfo({ status: 'REJECTED', response: 'Trang vẫn đòi mật khẩu ngân hàng' })),
        blocked('bad1111', appealInfo({ status: 'REJECTED' })),
      ),
    });
    renderApp('/dashboard', { authed: true });
    await screen.findByRole('link', { name: 'sho.rt/bad0000' });

    const withReply = within(rowOf('bad0000'));
    expect(withReply.getByText('Kháng nghị bị từ chối: Trang vẫn đòi mật khẩu ngân hàng')).toBeInTheDocument();
    expect(withReply.getByRole('button', { name: 'Kháng nghị lại' })).toBeInTheDocument();
    expect(within(rowOf('bad1111')).getByText('Kháng nghị bị từ chối')).toBeInTheDocument(); // không có phản hồi

    await userEvent.click(withReply.getByRole('button', { name: 'Thêm thao tác' }));
    expect(screen.getByRole('menuitem', { name: 'Kháng nghị lại' })).toBeInTheDocument();
  });

  it('chỉ link bị chặn mới có kháng nghị; mục menu mở được hộp thoại', async () => {
    serve({
      'GET /api/v1/users/me': me,
      'GET /api/v1/urls': listWith(link('abc1234'), blocked('bad0000')),
    });
    renderApp('/dashboard', { authed: true });
    await screen.findByRole('link', { name: 'sho.rt/bad0000' });

    expect(within(rowOf('abc1234')).queryByRole('button', { name: 'Kháng nghị' })).not.toBeInTheDocument();
    await userEvent.click(within(rowOf('abc1234')).getByRole('button', { name: 'Thêm thao tác' }));
    expect(screen.queryByRole('menuitem', { name: /Kháng nghị/ })).not.toBeInTheDocument();
    await userEvent.keyboard('{Escape}');

    await userEvent.click(within(rowOf('bad0000')).getByRole('button', { name: 'Thêm thao tác' }));
    await userEvent.click(screen.getByRole('menuitem', { name: 'Kháng nghị' }));
    expect(dialog().getByLabelText('Nội dung kháng nghị')).toHaveFocus();
  });

  it('lỗi từ server (đã có kháng nghị đang chờ) hiện trong hộp thoại và hộp thoại vẫn mở', async () => {
    serve({
      'GET /api/v1/users/me': me,
      'GET /api/v1/urls': listWith(blocked('bad0000')),
      'POST /api/v1/urls/bad0000/appeal': () => fail(409, 'ERR_412', 'Link này đã có một kháng nghị đang chờ xem xét.'),
    });
    renderApp('/dashboard', { authed: true });
    await screen.findByRole('link', { name: 'sho.rt/bad0000' });

    await userEvent.click(within(rowOf('bad0000')).getByRole('button', { name: 'Kháng nghị' }));
    await userEvent.type(dialog().getByLabelText('Nội dung kháng nghị'), 'xin xem lại');
    await userEvent.click(dialog().getByRole('button', { name: 'Gửi kháng nghị' }));

    expect(await dialog().findByText('Link này đã có một kháng nghị đang chờ xem xét.')).toBeInTheDocument();
    expect(dialog().getByLabelText('Nội dung kháng nghị')).toHaveValue('xin xem lại');
  });

  it('trang chi tiết link bị chặn cũng hiện trạng thái kháng nghị', async () => {
    const stats: UrlStats = {
      shortCode: 'bad0000',
      from: '2026-09-12',
      to: '2026-10-11',
      totals: { clicks: 0, botClicks: 0, uniqueVisitors: 0 },
      daily: [],
      countries: [],
      devices: [],
      browsers: [],
      operatingSystems: [],
    };
    serve({
      'GET /api/v1/users/me': me,
      'GET /api/v1/urls/bad0000': () => ok(blocked('bad0000', appealInfo({ status: 'REJECTED', response: 'Không đạt' }))),
      'GET /api/v1/urls/bad0000/stats': () => ok(stats),
    });
    renderApp('/links/bad0000', { authed: true });

    expect(await screen.findByText('Kháng nghị bị từ chối: Không đạt')).toBeInTheDocument();
    expect(screen.getByText(/Quản trị viên đã chặn: phishing/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Kháng nghị lại' })).toBeInTheDocument();
  });
});

// ===========================
//  Phía admin
// ===========================

const appeal = (id: number, code: string, extra: Partial<AdminAppeal> = {}): AdminAppeal => ({
  id,
  shortCode: code,
  shortUrl: `https://sho.rt/${code}`,
  originalUrl: `https://example.com/${code}`,
  linkStatus: 'BLOCKED',
  blockedReason: 'phishing',
  owner: 'alice',
  message: 'Đây là trang của tôi',
  status: 'OPEN',
  createdAt: '2026-10-10T09:00:00',
  updatedAt: '2026-10-10T09:00:00',
  ...extra,
});
const appealList = (...items: AdminAppeal[]) => () => ok(pageOf(items as never));
const cardOf = (code: string) => screen.getByText(`sho.rt/${code}`).closest('.link-card') as HTMLElement;

describe('kháng nghị (quản trị)', () => {
  it('người thường bị đưa về danh sách link của mình và không gọi API admin', async () => {
    serve({ 'GET /api/v1/users/me': me, 'GET /api/v1/urls': () => ok(pageOf([])) });
    renderApp('/admin/appeals', { authed: true });

    await screen.findByText('Tạo link rút gọn đầu tiên của bạn');
    expect(callsTo('GET', '/api/v1/admin/appeals')).toHaveLength(0);
  });

  it('mặc định tab Chờ xem xét, hiện link, chủ link, lý do chặn và nội dung; có mục trong thanh bên', async () => {
    serve({
      'GET /api/v1/users/me': adminMe,
      'GET /api/v1/admin/appeals': appealList(appeal(1, 'bad0000'), appeal(2, 'bad1111', { owner: 'bob', message: 'Nhầm rồi' })),
    });
    renderApp('/admin/appeals', { authed: true });

    await screen.findByText('sho.rt/bad0000');
    expect(callsTo('GET', '/api/v1/admin/appeals')[0].query.get('status')).toBe('OPEN');
    const card = within(cardOf('bad0000'));
    expect(card.getByText('Chủ link: alice')).toBeInTheDocument();
    expect(card.getByText('Bị chặn vì: phishing')).toBeInTheDocument();
    expect(card.getByText('Đây là trang của tôi')).toBeInTheDocument();
    expect(within(cardOf('bad1111')).getByText('Nhầm rồi')).toBeInTheDocument();
    expect(screen.getByText('2 kháng nghị')).toBeInTheDocument();
    expect(within(screen.getByRole('navigation', { name: 'Main' })).getByRole('link', { name: 'Kháng nghị' })).toHaveAttribute('href', '/admin/appeals');
  });

  it('chấp nhận: hỏi xác nhận, gọi accept, báo toast và tải lại danh sách', async () => {
    let listed = 0;
    serve({
      'GET /api/v1/users/me': adminMe,
      'GET /api/v1/admin/appeals': () => ok(pageOf(listed++ === 0 ? ([appeal(7, 'bad0000')] as never) : [])),
      'PUT /api/v1/admin/appeals/7/accept': () => ok(appeal(7, 'bad0000', { status: 'ACCEPTED', linkStatus: 'ACTIVE' })),
    });
    renderApp('/admin/appeals', { authed: true });
    await screen.findByText('sho.rt/bad0000');

    await userEvent.click(within(cardOf('bad0000')).getByRole('button', { name: 'Chấp nhận và gỡ chặn bad0000' }));
    expect(callsTo('PUT', '/api/v1/admin/appeals/7/accept')).toHaveLength(0); // chưa xác nhận thì chưa gỡ chặn
    await userEvent.click(dialog().getByRole('button', { name: 'Chấp nhận và gỡ chặn' }));

    expect(await screen.findByText('Không có kháng nghị nào đang chờ')).toBeInTheDocument();
    expect(callsTo('PUT', '/api/v1/admin/appeals/7/accept')).toHaveLength(1);
    expect(screen.getByText('Đã chấp nhận kháng nghị, link đã được gỡ chặn')).toBeInTheDocument();
  });

  it('từ chối: gửi phản hồi đã cắt khoảng trắng; để trống thì không gửi trường response', async () => {
    serve({
      'GET /api/v1/users/me': adminMe,
      'GET /api/v1/admin/appeals': appealList(appeal(7, 'bad0000'), appeal(8, 'bad1111')),
      'PUT /api/v1/admin/appeals/7/reject': () => ok(appeal(7, 'bad0000', { status: 'REJECTED' })),
      'PUT /api/v1/admin/appeals/8/reject': () => ok(appeal(8, 'bad1111', { status: 'REJECTED' })),
    });
    renderApp('/admin/appeals', { authed: true });
    await screen.findByText('sho.rt/bad0000');

    await userEvent.click(within(cardOf('bad0000')).getByRole('button', { name: 'Từ chối bad0000' }));
    await userEvent.type(dialog().getByLabelText('Phản hồi cho chủ link'), '  Trang vẫn lừa đảo  ');
    await userEvent.click(dialog().getByRole('button', { name: 'Từ chối kháng nghị' }));
    await screen.findByText('Đã từ chối kháng nghị');
    expect(callsTo('PUT', '/api/v1/admin/appeals/7/reject')[0].body).toEqual({ response: 'Trang vẫn lừa đảo' });

    await userEvent.click(within(cardOf('bad1111')).getByRole('button', { name: 'Từ chối bad1111' }));
    await userEvent.click(dialog().getByRole('button', { name: 'Từ chối kháng nghị' }));
    await vi.waitFor(() => expect(callsTo('PUT', '/api/v1/admin/appeals/8/reject')).toHaveLength(1));
    expect(callsTo('PUT', '/api/v1/admin/appeals/8/reject')[0].body).toEqual({});
  });

  it('lỗi 409 (admin khác đã xử lý) hiện trong hộp thoại, không đóng', async () => {
    serve({
      'GET /api/v1/users/me': adminMe,
      'GET /api/v1/admin/appeals': appealList(appeal(7, 'bad0000')),
      'PUT /api/v1/admin/appeals/7/accept': () => fail(409, 'ERR_414', 'Kháng nghị 7 đã được xem xét.'),
    });
    renderApp('/admin/appeals', { authed: true });
    await screen.findByText('sho.rt/bad0000');

    await userEvent.click(within(cardOf('bad0000')).getByRole('button', { name: 'Chấp nhận và gỡ chặn bad0000' }));
    await userEvent.click(dialog().getByRole('button', { name: 'Chấp nhận và gỡ chặn' }));

    expect(await dialog().findByText('Kháng nghị 7 đã được xem xét.')).toBeInTheDocument();
  });

  it('tab đã xử lý gửi status tương ứng, hiện phản hồi và không có nút hành động; tab rỗng giải thích', async () => {
    serve({
      'GET /api/v1/users/me': adminMe,
      'GET /api/v1/admin/appeals': (c) =>
        c.query.get('status') === 'REJECTED'
          ? ok(pageOf([appeal(3, 'old1234', { status: 'REJECTED', response: 'Vẫn vi phạm', updatedAt: '2026-10-11T10:00:00' })] as never))
          : ok(pageOf([])),
    });
    renderApp('/admin/appeals', { authed: true });
    await screen.findByText('Không có kháng nghị nào đang chờ');

    await userEvent.click(screen.getByRole('button', { name: 'Đã từ chối' }));
    await screen.findByText('sho.rt/old1234');

    expect(callsTo('GET', '/api/v1/admin/appeals').at(-1)!.query.get('status')).toBe('REJECTED');
    const card = within(cardOf('old1234'));
    expect(card.getByText('Phản hồi: Vẫn vi phạm')).toBeInTheDocument();
    expect(card.queryByRole('button', { name: /^Chấp nhận/ })).not.toBeInTheDocument();
    expect(card.queryByRole('button', { name: /^Từ chối/ })).not.toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Đã chấp nhận' }));
    expect(await screen.findByText('Chưa có kháng nghị nào được chấp nhận')).toBeInTheDocument();
  });
});
