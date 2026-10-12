import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { AbuseSummary } from '../api/types';
import { callsTo, fail, mockMatchMedia, ok, pageOf, renderApp, resetServer, serve, USER } from '../test/helpers';

beforeEach(() => {
  resetServer();
  mockMatchMedia(() => false);
});
afterEach(() => vi.unstubAllGlobals());

const ADMIN = { ...USER, id: 'admin-1', username: 'root', fullName: 'Root Admin', roles: ['ROLE_USER', 'ROLE_ADMIN'] };
const adminMe = () => ok(ADMIN);

const summary = (code: string): AbuseSummary => ({
  shortCode: code,
  shortUrl: `https://sho.rt/${code}`,
  originalUrl: `https://example.com/${code}`,
  linkStatus: 'ACTIVE',
  reportCount: 2,
  lastReportedAt: '2026-10-10T09:00:00',
  reasons: { SPAM: 2 },
});
const cardOf = (code: string) => screen.getByText(`sho.rt/${code}`).closest('.link-card') as HTMLElement;
const tab = (name: string) => screen.getByRole('button', { name });

/** `dataTransfer` giả: jsdom không có DataTransfer, React chỉ cần các trường này. */
const dataTransfer = () => ({ setData: vi.fn(), getData: vi.fn(), effectAllowed: '', dropEffect: '' });

/** Danh sách báo cáo giả theo tab: mỗi tab có link riêng, và sau khi chuyển link rời khỏi tab nguồn sang tab đích. */
function serveReports(extra: Parameters<typeof serve>[0] = {}) {
  const tabs: Record<string, AbuseSummary[]> = { OPEN: [summary('abc1234')], RESOLVED: [summary('old1234')], DISMISSED: [] };
  serve({
    'GET /api/v1/users/me': adminMe,
    'GET /api/v1/admin/reports': (c) => ok(pageOf((tabs[c.query.get('status') ?? 'OPEN'] ?? []) as never)),
    'PUT /api/v1/admin/reports/abc1234/move': (c) => {
      const { from, to } = c.body as { from: string; to: string };
      tabs[to].push(...tabs[from].splice(0));
      return ok(2);
    },
    'PUT /api/v1/admin/reports/old1234/move': (c) => {
      const { from, to } = c.body as { from: string; to: string };
      tabs[to].push(...tabs[from].splice(0));
      return ok(1);
    },
    ...extra,
  });
}

describe('quản trị: chuyển báo cáo giữa các tab', () => {
  it('kéo thẻ thả lên tab khác: gửi from/to đúng, báo toast và thẻ rời khỏi tab hiện tại', async () => {
    serveReports();
    renderApp('/admin/reports', { authed: true });
    await screen.findByText('sho.rt/abc1234');

    const dt = dataTransfer();
    fireEvent.dragStart(cardOf('abc1234'), { dataTransfer: dt });
    expect(dt.setData).toHaveBeenCalledWith('text/plain', 'abc1234');
    // Trong lúc kéo: tab khác là chỗ thả được, tab hiện tại thì không
    expect(tab('Đã bỏ qua')).toHaveClass('drop-ok');
    expect(tab('Chờ xử lý')).not.toHaveClass('drop-ok');
    fireEvent.dragOver(tab('Đã bỏ qua'), { dataTransfer: dt });
    expect(tab('Đã bỏ qua')).toHaveClass('drop-over');
    fireEvent.drop(tab('Đã bỏ qua'), { dataTransfer: dt });

    expect(await screen.findByText('Đã chuyển sang "Đã bỏ qua"')).toBeInTheDocument();
    expect(callsTo('PUT', '/api/v1/admin/reports/abc1234/move')[0].body).toEqual({ from: 'OPEN', to: 'DISMISSED' });
    await waitFor(() => expect(screen.queryByText('sho.rt/abc1234')).not.toBeInTheDocument());
    expect(tab('Đã bỏ qua')).not.toHaveClass('drop-ok'); // hết kéo thì bỏ nét đứt
  });

  it('kéo ngược từ tab đã xử lý về Chờ xử lý (chiều ngược lại cũng được)', async () => {
    serveReports();
    renderApp('/admin/reports', { authed: true });
    await screen.findByText('sho.rt/abc1234');
    await userEvent.click(tab('Đã xử lý'));
    await screen.findByText('sho.rt/old1234');

    const dt = dataTransfer();
    fireEvent.dragStart(cardOf('old1234'), { dataTransfer: dt });
    fireEvent.dragOver(tab('Chờ xử lý'), { dataTransfer: dt });
    fireEvent.drop(tab('Chờ xử lý'), { dataTransfer: dt });

    await waitFor(() => expect(callsTo('PUT', '/api/v1/admin/reports/old1234/move')).toHaveLength(1));
    expect(callsTo('PUT', '/api/v1/admin/reports/old1234/move')[0].body).toEqual({ from: 'RESOLVED', to: 'OPEN' });
  });

  it('thả lên chính tab đang xem không gửi gì', async () => {
    serveReports();
    renderApp('/admin/reports', { authed: true });
    await screen.findByText('sho.rt/abc1234');

    const dt = dataTransfer();
    fireEvent.dragStart(cardOf('abc1234'), { dataTransfer: dt });
    fireEvent.drop(tab('Chờ xử lý'), { dataTransfer: dt });

    expect(callsTo('PUT', '/api/v1/admin/reports/abc1234/move')).toHaveLength(0);
  });

  it('kết thúc kéo mà không thả thì bỏ nét đứt trên các tab', async () => {
    serveReports();
    renderApp('/admin/reports', { authed: true });
    await screen.findByText('sho.rt/abc1234');

    fireEvent.dragStart(cardOf('abc1234'), { dataTransfer: dataTransfer() });
    expect(tab('Đã xử lý')).toHaveClass('drop-ok');
    fireEvent.dragEnd(cardOf('abc1234'));

    expect(tab('Đã xử lý')).not.toHaveClass('drop-ok');
  });

  it('menu "Chuyển tới…" cho màn hình cảm ứng và bàn phím: chỉ liệt kê hai tab còn lại', async () => {
    serveReports();
    renderApp('/admin/reports', { authed: true });
    await screen.findByText('sho.rt/abc1234');

    await userEvent.click(within(cardOf('abc1234')).getByRole('button', { name: 'Chuyển tới… abc1234' }));
    expect(screen.queryByRole('menuitem', { name: 'Chờ xử lý' })).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('menuitem', { name: 'Đã xử lý' }));

    await screen.findByText('Đã chuyển sang "Đã xử lý"');
    expect(callsTo('PUT', '/api/v1/admin/reports/abc1234/move')[0].body).toEqual({ from: 'OPEN', to: 'RESOLVED' });
  });

  it('tab đã xử lý cũng có menu "Chuyển tới…" (nhưng vẫn không có nút Chặn / Đánh dấu / Bỏ qua)', async () => {
    serveReports();
    renderApp('/admin/reports', { authed: true });
    await screen.findByText('sho.rt/abc1234');
    await userEvent.click(tab('Đã xử lý'));
    await screen.findByText('sho.rt/old1234');

    const card = within(cardOf('old1234'));
    expect(card.getByRole('button', { name: 'Chuyển tới… old1234' })).toBeInTheDocument();
    expect(card.queryByRole('button', { name: /^Chặn link/ })).not.toBeInTheDocument();
    expect(card.queryByRole('button', { name: /^Bỏ qua/ })).not.toBeInTheDocument();
    await userEvent.click(card.getByRole('button', { name: 'Chuyển tới… old1234' }));
    expect(screen.getByRole('menuitem', { name: 'Chờ xử lý' })).toBeInTheDocument();
    expect(screen.getByRole('menuitem', { name: 'Đã bỏ qua' })).toBeInTheDocument();
  });

  it('server báo 0 báo cáo được chuyển (người khác vừa đổi): báo lỗi nhẹ và tải lại danh sách', async () => {
    serveReports({ 'PUT /api/v1/admin/reports/abc1234/move': () => ok(0) });
    renderApp('/admin/reports', { authed: true });
    await screen.findByText('sho.rt/abc1234');
    const before = callsTo('GET', '/api/v1/admin/reports').length;

    await userEvent.click(within(cardOf('abc1234')).getByRole('button', { name: 'Chuyển tới… abc1234' }));
    await userEvent.click(screen.getByRole('menuitem', { name: 'Đã bỏ qua' }));

    expect(await screen.findByText(/đã được người khác thay đổi/)).toBeInTheDocument();
    await waitFor(() => expect(callsTo('GET', '/api/v1/admin/reports').length).toBeGreaterThan(before));
  });

  it('lỗi từ server khi chuyển hiện thành toast lỗi', async () => {
    serveReports({ 'PUT /api/v1/admin/reports/abc1234/move': () => fail(404, 'ERR_400', 'Link không tồn tại') });
    renderApp('/admin/reports', { authed: true });
    await screen.findByText('sho.rt/abc1234');

    await userEvent.click(within(cardOf('abc1234')).getByRole('button', { name: 'Chuyển tới… abc1234' }));
    await userEvent.click(screen.getByRole('menuitem', { name: 'Đã xử lý' }));

    expect(await screen.findByText('Link không tồn tại')).toBeInTheDocument();
  });
});
