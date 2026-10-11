import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { me, ok, pageOf, renderApp, resetServer, serve } from '../test/helpers';

beforeEach(() => resetServer());
afterEach(() => vi.unstubAllGlobals());

function serveShell() {
  serve({ 'GET /api/v1/users/me': me, 'GET /api/v1/urls': () => ok(pageOf([])) });
}

describe('thanh bên của khung ứng dụng', () => {
  it('có Link của tôi, Cài đặt và Trợ giúp & hỏi đáp; không còn nút Trang chủ thừa', async () => {
    serveShell();
    renderApp('/dashboard', { authed: true });
    await screen.findByRole('heading', { name: 'Link của tôi', level: 1 });

    const nav = within(screen.getAllByRole('navigation', { name: 'Main' })[0]);
    expect(nav.getByRole('link', { name: 'Link của tôi' })).toHaveAttribute('href', '/dashboard');
    expect(nav.getByRole('link', { name: 'Cài đặt' })).toHaveAttribute('href', '/settings');
    expect(screen.getByRole('link', { name: 'Trợ giúp & hỏi đáp' })).toHaveAttribute('href', '/#faq');
    expect(screen.queryByRole('link', { name: 'Trang chủ' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Trang chủ' })).not.toBeInTheDocument();
  });

  it('logo dẫn về trang chính của ứng dụng (danh sách link), không phải trang giới thiệu', async () => {
    serveShell();
    renderApp('/settings', { authed: true });
    await screen.findByRole('heading', { name: 'Cài đặt', level: 1 });

    expect(screen.getByRole('link', { name: 'Shortly' })).toHaveAttribute('href', '/dashboard');
  });

  it('bấm Trợ giúp & hỏi đáp mở trang chủ và cuộn tới phần Hỏi đáp', async () => {
    const scrollIntoView = vi.fn();
    Element.prototype.scrollIntoView = scrollIntoView;
    serveShell();
    renderApp('/dashboard', { authed: true });
    await screen.findByRole('heading', { name: 'Link của tôi', level: 1 });

    await userEvent.click(screen.getByRole('link', { name: 'Trợ giúp & hỏi đáp' }));

    expect(await screen.findByRole('heading', { name: 'Câu hỏi thường gặp' })).toBeInTheDocument();
    expect(scrollIntoView.mock.contexts.at(-1)).toBe(document.getElementById('faq'));
  });

  it('bản tiếng Anh', async () => {
    resetServer('en');
    serveShell();
    renderApp('/dashboard', { authed: true });
    await screen.findByRole('heading', { name: 'Links', level: 1 });

    expect(screen.getByRole('link', { name: 'Help & FAQ' })).toHaveAttribute('href', '/#faq');
  });
});
