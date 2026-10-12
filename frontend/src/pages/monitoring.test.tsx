import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { LogEntry, LogSummary, MonitoringStatus } from '../api/types';
import { callsTo, fail, mockMatchMedia, ok, pageOf, renderApp, resetServer, serve, USER, me } from '../test/helpers';

beforeEach(() => {
  resetServer();
  mockMatchMedia(() => false);
});
afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

const ADMIN = { ...USER, id: 'admin-1', username: 'root', fullName: 'Root Admin', roles: ['ROLE_USER', 'ROLE_ADMIN'] };
const adminMe = () => ok(ADMIN);

const STATUS: MonitoringStatus = { configured: true, reachable: true, uiUrl: 'http://localhost:5080' };
const SUMMARY: LogSummary = {
  minutes: 60,
  levels: { ERROR: 3, WARN: 12, INFO: 1500, DEBUG: 40000, TRACE: 0 },
  topExceptions: [{ type: 'java.lang.IllegalStateException', count: 2 }],
};
const entry = (message: string, extra: Partial<LogEntry> = {}): LogEntry => ({
  timestamp: '2026-10-12T22:10:00',
  level: 'ERROR',
  logger: 'com.auradev.url_shortener.service.impl.Foo',
  message,
  ...extra,
});

function serveMonitoring(extra: Parameters<typeof serve>[0] = {}, logs: LogEntry[] = [entry('Something failed')]) {
  serve({
    'GET /api/v1/users/me': adminMe,
    'GET /api/v1/admin/monitoring/status': () => ok(STATUS),
    'GET /api/v1/admin/monitoring/summary': () => ok(SUMMARY),
    'GET /api/v1/admin/monitoring/logs': () => ok(pageOf(logs as never)),
    ...extra,
  });
}
const lastLogsQuery = () => callsTo('GET', '/api/v1/admin/monitoring/logs').at(-1)!.query;
const levelCard = (name: RegExp) => screen.getByRole('button', { name });

describe('quản trị: giám sát (log từ OpenObserve)', () => {
  it('người thường bị đưa về danh sách link và không gọi API giám sát', async () => {
    serve({ 'GET /api/v1/users/me': me, 'GET /api/v1/urls': () => ok(pageOf([])) });
    renderApp('/admin/monitoring', { authed: true });

    await screen.findByText('Tạo link rút gọn đầu tiên của bạn');
    expect(callsTo('GET', '/api/v1/admin/monitoring/status')).toHaveLength(0);
  });

  it('có mục "Giám sát" trong thanh bên; hiện trạng thái kết nối, đếm theo mức, top exception và các dòng log', async () => {
    serveMonitoring();
    renderApp('/admin/monitoring', { authed: true });

    expect(await screen.findByText('Something failed')).toBeInTheDocument();
    expect(within(screen.getByRole('navigation', { name: 'Main' })).getByRole('link', { name: 'Giám sát' })).toHaveAttribute('href', '/admin/monitoring');
    expect(screen.getByText('Đã kết nối')).toBeInTheDocument();
    expect(await screen.findByText('java.lang.IllegalStateException')).toBeInTheDocument(); // summary đến sau logs
    expect(levelCard(/Lỗi/)).toHaveTextContent('3');
    expect(levelCard(/Cảnh báo/)).toHaveTextContent('12');
    expect(levelCard(/Thông tin/)).toHaveTextContent('1.500');
    // Mặc định: lỗi và cảnh báo, 1 giờ; chỉ hai mức đó đang bật
    expect(lastLogsQuery().get('levels')).toBe('ERROR,WARN');
    expect(lastLogsQuery().get('minutes')).toBe('60');
    expect(levelCard(/Lỗi/)).toHaveAttribute('aria-pressed', 'true');
    expect(levelCard(/Thông tin/)).toHaveAttribute('aria-pressed', 'false');
    expect(screen.getByText('1 dòng log')).toBeInTheDocument();
  });

  it('nút "Mở OpenObserve" mở giao diện OpenObserve ở tab mới, không lộ opener', async () => {
    serveMonitoring();
    renderApp('/admin/monitoring', { authed: true });

    const link = await screen.findByRole('link', { name: 'Mở OpenObserve' });
    expect(link).toHaveAttribute('href', 'http://localhost:5080');
    expect(link).toHaveAttribute('target', '_blank');
    expect(link).toHaveAttribute('rel', expect.stringContaining('noopener'));
  });

  it('OpenObserve không trả lời: báo trên huy hiệu; lỗi tải log hiện kèm nút thử lại', async () => {
    let attempts = 0;
    serveMonitoring({
      'GET /api/v1/admin/monitoring/status': () => ok({ ...STATUS, reachable: false }),
      'GET /api/v1/admin/monitoring/logs': () => (attempts++ === 0 ? fail(502, 'ERR_601', 'Hiện không đọc được log từ OpenObserve.') : ok(pageOf([entry('Back again')] as never))),
    });
    renderApp('/admin/monitoring', { authed: true });

    expect(await screen.findByText('OpenObserve không phản hồi')).toBeInTheDocument();
    expect(await screen.findByText('Hiện không đọc được log từ OpenObserve.')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Thử lại' }));
    expect(await screen.findByText('Back again')).toBeInTheDocument();
  });

  it('chưa cấu hình: giải thích cách thiết lập và không gọi summary / logs', async () => {
    serveMonitoring({ 'GET /api/v1/admin/monitoring/status': () => ok({ configured: false, reachable: false }) });
    renderApp('/admin/monitoring', { authed: true });

    expect(await screen.findByText('Chưa thiết lập giám sát')).toBeInTheDocument();
    expect(screen.getByText(/OPENOBSERVE_URL/)).toBeInTheDocument();
    expect(callsTo('GET', '/api/v1/admin/monitoring/summary')).toHaveLength(0);
    expect(callsTo('GET', '/api/v1/admin/monitoring/logs')).toHaveLength(0);
  });

  it('bấm thẻ mức để bật / tắt; luôn còn ít nhất một mức', async () => {
    serveMonitoring();
    renderApp('/admin/monitoring', { authed: true });
    await screen.findByText('Something failed');

    await userEvent.click(levelCard(/Thông tin/));
    await waitFor(() => expect(lastLogsQuery().get('levels')).toBe('ERROR,WARN,INFO'));
    await userEvent.click(levelCard(/Lỗi/));
    await userEvent.click(levelCard(/Cảnh báo/));
    await waitFor(() => expect(lastLogsQuery().get('levels')).toBe('INFO'));

    await userEvent.click(levelCard(/Thông tin/)); // mức cuối cùng: không tắt được
    expect(levelCard(/Thông tin/)).toHaveAttribute('aria-pressed', 'true');
  });

  it('đổi khoảng thời gian gửi minutes cho cả summary lẫn logs', async () => {
    serveMonitoring();
    renderApp('/admin/monitoring', { authed: true });
    await screen.findByText('Something failed');

    await userEvent.click(screen.getByRole('button', { name: '6 giờ' }));

    await waitFor(() => expect(lastLogsQuery().get('minutes')).toBe('360'));
    expect(callsTo('GET', '/api/v1/admin/monitoring/summary').at(-1)!.query.get('minutes')).toBe('360');
  });

  it('tìm trong nội dung log gửi keyword (đã cắt khoảng trắng) và về trang đầu', async () => {
    serveMonitoring();
    renderApp('/admin/monitoring', { authed: true });
    await screen.findByText('Something failed');

    await userEvent.type(screen.getByRole('searchbox', { name: 'Tìm trong nội dung log' }), '  redirect  ');

    await waitFor(() => expect(lastLogsQuery().get('keyword')).toBe('redirect'));
    expect(lastLogsQuery().get('page')).toBe('0');
  });

  it('dòng có exception: bung ra xem loại, stacktrace và mã trace; ẩn lại được', async () => {
    serveMonitoring({}, [
      entry('Request failed', {
        exceptionType: 'java.lang.IllegalStateException',
        exceptionMessage: 'bad state',
        exceptionStacktrace: 'at com.example.Foo.bar(Foo.java:42)',
        traceId: '0af7651916cd43dd8448eb211c80319c',
      }),
      entry('Plain warning', { level: 'WARN' }),
    ]);
    renderApp('/admin/monitoring', { authed: true });
    await screen.findByText('Request failed');

    // Dòng không có chi tiết thì không có nút
    expect(screen.getAllByRole('button', { name: 'Chi tiết' })).toHaveLength(1);
    await userEvent.click(screen.getByRole('button', { name: 'Chi tiết' }));

    expect(screen.getByText(/bad state/)).toBeInTheDocument();
    expect(screen.getByText('at com.example.Foo.bar(Foo.java:42)')).toBeInTheDocument();
    expect(screen.getByText('0af7651916cd43dd8448eb211c80319c')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Ẩn' }));
    expect(screen.queryByText('at com.example.Foo.bar(Foo.java:42)')).not.toBeInTheDocument();
  });

  it('tự làm mới: bật thì tải lại mỗi 10 giây, tắt thì dừng', async () => {
    serveMonitoring();
    renderApp('/admin/monitoring', { authed: true });
    await screen.findByText('Something failed');
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const before = callsTo('GET', '/api/v1/admin/monitoring/logs').length;

    await userEvent.click(screen.getByRole('checkbox', { name: 'Tự làm mới (10 giây)' }));
    await act(async () => {
      await vi.advanceTimersByTimeAsync(10_500);
    });
    await waitFor(() => expect(callsTo('GET', '/api/v1/admin/monitoring/logs').length).toBeGreaterThan(before));
    const during = callsTo('GET', '/api/v1/admin/monitoring/logs').length;

    await userEvent.click(screen.getByRole('checkbox', { name: 'Tự làm mới (10 giây)' }));
    await act(async () => {
      await vi.advanceTimersByTimeAsync(25_000);
    });
    expect(callsTo('GET', '/api/v1/admin/monitoring/logs').length).toBe(during);
  });

  it('không có dòng log nào khớp: hiện lời giải thích', async () => {
    serveMonitoring({}, []);
    renderApp('/admin/monitoring', { authed: true });

    expect(await screen.findByText('Không có dòng log nào')).toBeInTheDocument();
  });
});
