import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { callsTo, created, fail, fetchMock, link, me, ok, pageOf, renderApp, resetServer, serve } from '../test/helpers';

// Người dùng ở UTC+7 còn máy chủ ở UTC (đúng tình huống prod: container UTC, người dùng ở Việt Nam)
vi.mock('../utils/timezone', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../utils/timezone')>();
  return { ...actual, browserZone: () => 'Asia/Ho_Chi_Minh' };
});

beforeEach(() => resetServer());
afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

const serverUtc = { 'GET /api/v1/public/config': () => ok({ timezone: 'UTC' }) };
const dialog = () => within(screen.getByRole('dialog'));
const configCalls = () => callsTo('GET', '/api/v1/public/config');

describe('hạn dùng khi máy chủ và người dùng khác múi giờ', () => {
  it('trang chủ: giờ người dùng nhập (UTC+7) được gửi lên theo giờ máy chủ (UTC)', async () => {
    serve({ ...serverUtc, 'POST /api/v1/public/urls': () => created(link('abc1234')) });
    renderApp('/');

    await userEvent.type(screen.getByLabelText('Link cần rút gọn'), 'https://example.com/x');
    await userEvent.click(screen.getByRole('button', { name: 'Tuỳ chọn thêm' }));
    await userEvent.type(screen.getByLabelText('Hết hạn lúc'), '2030-01-02T10:04');
    await userEvent.click(screen.getByRole('button', { name: /Rút gọn/ }));

    await screen.findByRole('link', { name: 'sho.rt/abc1234' });
    expect(callsTo('POST', '/api/v1/public/urls')[0].body).toEqual({ originalUrl: 'https://example.com/x', expiresAt: '2030-01-02T03:04:00' });
  });

  it('ô hạn dùng ghi rõ múi giờ đang dùng để người dùng không phải đoán', async () => {
    serve({ ...serverUtc });
    renderApp('/');

    await userEvent.click(screen.getByRole('button', { name: 'Tuỳ chọn thêm' }));

    expect(await screen.findByText(/Giờ theo múi giờ của bạn \(.*GMT\+7.*Asia\/Ho_Chi_Minh\)/)).toBeInTheDocument();
  });

  it('tạo link trong hộp thoại cũng quy đổi, kể cả khi giờ lệch sang ngày hôm trước', async () => {
    serve({
      ...serverUtc,
      'GET /api/v1/users/me': me,
      'GET /api/v1/urls': () => ok(pageOf([])),
      'POST /api/v1/urls': () => created(link('abc1234')),
    });
    renderApp('/dashboard', { authed: true });
    await screen.findByText('Tạo link rút gọn đầu tiên của bạn');

    await userEvent.click(screen.getAllByRole('button', { name: 'Tạo link' })[0]);
    await userEvent.type(dialog().getByLabelText('URL đích'), 'https://example.com/x');
    await userEvent.click(dialog().getByRole('button', { name: 'Tuỳ chọn nâng cao' }));
    await userEvent.type(dialog().getByLabelText('Hết hạn lúc'), '2030-01-02T02:00');
    await userEvent.click(dialog().getByRole('button', { name: 'Tạo link' }));

    await waitFor(() => expect(callsTo('POST', '/api/v1/urls')).toHaveLength(1));
    expect(callsTo('POST', '/api/v1/urls')[0].body).toEqual({ originalUrl: 'https://example.com/x', expiresAt: '2030-01-01T19:00:00' });
  });

  it('danh sách hiện hạn dùng theo giờ người dùng (qua cả nửa đêm) và ngày tạo theo giờ người dùng', async () => {
    serve({
      ...serverUtc,
      'GET /api/v1/users/me': me,
      'GET /api/v1/urls': () =>
        ok(pageOf([link('abc1234', { expiresAt: '2026-10-11T23:30:00', createdAt: '2026-10-11T22:00:00' })])),
    });
    renderApp('/dashboard', { authed: true });

    const row = (await screen.findByRole('link', { name: 'sho.rt/abc1234' })).closest('tr') as HTMLElement;
    // Giờ chưa quy đổi (23:30 ngày 11) không bao giờ được hiện: danh sách chỉ dựng khi đã biết múi giờ máy chủ
    expect(within(row).queryByText(/23:30/)).not.toBeInTheDocument();

    // 23:30 UTC ngày 11 = 06:30 ngày 12 ở UTC+7; 22:00 UTC ngày 11 = 05:00 ngày 12
    // tiếng Việt viết giờ trước ngày ("06:30 12 thg 10, 2026"), nên kiểm từng phần thay vì một thứ tự cố định
    expect(
      within(row).getByText((text) => text.startsWith('Hết hạn') && text.includes('06:30') && text.includes('12') && text.includes('2026')),
    ).toBeInTheDocument();
    expect(within(row).getByText(/12.*2026/, { selector: 'td.muted' })).toBeInTheDocument();
  });

  it('sửa link: ô hạn dùng điền sẵn theo giờ người dùng, chỉ đổi đích thì hạn dùng gửi lên đúng giá trị cũ của server', async () => {
    serve({
      ...serverUtc,
      'GET /api/v1/users/me': me,
      'GET /api/v1/urls': () => ok(pageOf([link('abc1234', { expiresAt: '2030-01-02T03:04:00' })])),
      'PUT /api/v1/urls/abc1234': () => ok(link('abc1234', { expiresAt: '2030-01-02T03:04:00' })),
    });
    renderApp('/dashboard', { authed: true });
    const row = (await screen.findByRole('link', { name: 'sho.rt/abc1234' })).closest('tr') as HTMLElement;

    await userEvent.click(within(row).getByRole('button', { name: 'Thêm thao tác' }));
    await userEvent.click(screen.getByRole('menuitem', { name: 'Sửa đích' }));
    expect(await dialog().findByLabelText('Hết hạn lúc')).toHaveValue('2030-01-02T10:04'); // 03:04 UTC = 10:04 UTC+7
    // Chưa đổi gì thì nút Lưu bị khoá; chỉ đổi đích, không đụng tới ô hạn dùng
    expect(dialog().getByRole('button', { name: 'Lưu thay đổi' })).toBeDisabled();
    await userEvent.type(dialog().getByLabelText('URL đích'), '/moi');
    await userEvent.click(dialog().getByRole('button', { name: 'Lưu thay đổi' }));

    await waitFor(() => expect(callsTo('PUT', '/api/v1/urls/abc1234')).toHaveLength(1));
    expect(callsTo('PUT', '/api/v1/urls/abc1234')[0].body).toEqual({
      originalUrl: 'https://example.com/abc1234/moi',
      expiresAt: '2030-01-02T03:04:00', // vòng đi-về múi giờ không làm trôi hạn dùng
    });
  });

  it('trang chi tiết: "30 ngày gần nhất" tính theo NGÀY CỦA MÁY CHỦ và có chú thích khi khác múi giờ', async () => {
    // 20:00 UTC ngày 11: đã là ngày 12 ở UTC+7 nhưng vẫn là ngày 11 ở máy chủ UTC
    vi.useFakeTimers({ toFake: ['Date'], now: new Date('2026-10-11T20:00:00Z') });
    serve({
      ...serverUtc,
      'GET /api/v1/users/me': me,
      'GET /api/v1/urls/abc1234': () => ok(link('abc1234', { createdAt: '2026-10-11T22:00:00' })),
      'GET /api/v1/urls/abc1234/stats': () =>
        ok({
          shortCode: 'abc1234',
          from: '2026-09-12',
          to: '2026-10-11',
          totals: { clicks: 0, botClicks: 0, uniqueVisitors: 0 },
          daily: [],
          countries: [],
          devices: [],
          browsers: [],
          operatingSystems: [],
        }),
      'GET /api/v1/urls/abc1234/qr': () => new Response(new Uint8Array([1]), { status: 200 }),
    });
    renderApp('/links/abc1234', { authed: true });

    await screen.findByText(/Chưa có lượt click nào/);
    const stats = callsTo('GET', '/api/v1/urls/abc1234/stats');
    expect(stats).toHaveLength(1); // đợi biết múi giờ rồi mới gọi, không gọi hai lần
    expect(stats[0].query.get('to')).toBe('2026-10-11');
    expect(stats[0].query.get('from')).toBe('2026-09-12');
    expect(screen.getByText(/Ngày được tính theo múi giờ của máy chủ/)).toBeInTheDocument();
  });

  it('cùng múi giờ thì không hiện chú thích và không quy đổi', async () => {
    serve({
      'GET /api/v1/public/config': () => ok({ timezone: 'Asia/Ho_Chi_Minh' }),
      'GET /api/v1/users/me': me,
      'GET /api/v1/urls/abc1234': () => ok(link('abc1234')),
      'GET /api/v1/urls/abc1234/stats': () =>
        ok({
          shortCode: 'abc1234',
          from: '2026-09-12',
          to: '2026-10-11',
          totals: { clicks: 0, botClicks: 0, uniqueVisitors: 0 },
          daily: [],
          countries: [],
          devices: [],
          browsers: [],
          operatingSystems: [],
        }),
      'GET /api/v1/urls/abc1234/qr': () => new Response(new Uint8Array([1]), { status: 200 }),
    });
    renderApp('/links/abc1234', { authed: true });

    await screen.findByText(/Chưa có lượt click nào/);

    expect(screen.queryByText(/Ngày được tính theo múi giờ của máy chủ/)).not.toBeInTheDocument();
  });
});

describe('khi không lấy được múi giờ máy chủ', () => {
  it('lỗi endpoint cấu hình: vẫn tạo được link, giá trị gửi nguyên như người dùng nhập', async () => {
    serve({
      'GET /api/v1/public/config': () => fail(500, 'ERR_500', 'Lỗi'),
      'POST /api/v1/public/urls': () => created(link('abc1234')),
    });
    renderApp('/');

    await userEvent.type(screen.getByLabelText('Link cần rút gọn'), 'https://example.com/x');
    await userEvent.click(screen.getByRole('button', { name: 'Tuỳ chọn thêm' }));
    await userEvent.type(screen.getByLabelText('Hết hạn lúc'), '2030-01-02T10:04');
    await userEvent.click(screen.getByRole('button', { name: /Rút gọn/ }));

    await screen.findByRole('link', { name: 'sho.rt/abc1234' });
    expect(callsTo('POST', '/api/v1/public/urls')[0].body).toEqual({ originalUrl: 'https://example.com/x', expiresAt: '2030-01-02T10:04:00' });
  });

  it('múi giờ máy chủ mà trình duyệt không hiểu cũng không làm vỡ gì', async () => {
    serve({
      'GET /api/v1/public/config': () => ok({ timezone: 'Khong/Co' }),
      'POST /api/v1/public/urls': () => created(link('abc1234')),
    });
    renderApp('/');

    await userEvent.type(screen.getByLabelText('Link cần rút gọn'), 'https://example.com/x');
    await userEvent.click(screen.getByRole('button', { name: 'Tuỳ chọn thêm' }));
    await userEvent.type(screen.getByLabelText('Hết hạn lúc'), '2030-01-02T10:04');
    await userEvent.click(screen.getByRole('button', { name: /Rút gọn/ }));

    await screen.findByRole('link', { name: 'sho.rt/abc1234' });
    expect(callsTo('POST', '/api/v1/public/urls')[0].body).toMatchObject({ expiresAt: '2030-01-02T10:04:00' });
  });
});

describe('số lần hỏi múi giờ', () => {
  it('nhiều dòng trong danh sách chỉ gây đúng một request cấu hình', async () => {
    serve({
      ...serverUtc,
      'GET /api/v1/users/me': me,
      'GET /api/v1/urls': () => ok(pageOf([link('aaa1111'), link('bbb2222'), link('ccc3333'), link('ddd4444')])),
    });
    renderApp('/dashboard', { authed: true });

    await screen.findByRole('link', { name: 'sho.rt/ddd4444' });

    expect(configCalls()).toHaveLength(1);
  });

  it('trang không dùng thời gian (đăng nhập, mở khoá) không hỏi gì', () => {
    fetchMock.mockImplementation(async () => {
      throw new Error('no network expected');
    });
    renderApp('/unlock/abc1234');

    expect(fetchMock).not.toHaveBeenCalled();
  });
});
