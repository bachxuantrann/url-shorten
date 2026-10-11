import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { me, ok, pageOf, renderApp, resetServer, serve, USER } from '../test/helpers';
import { initialsOf } from './UserMenu';

beforeEach(() => resetServer());
afterEach(() => vi.unstubAllGlobals());

describe('initialsOf', () => {
  it('chữ cái đầu của hai từ đầu, viết hoa', () => {
    expect(initialsOf('Trần Xuân Bách')).toBe('TX');
    expect(initialsOf('alice nguyen')).toBe('AN');
    expect(initialsOf('Alice')).toBe('A');
  });

  it('chỉ lấy tối đa hai chữ cái và bỏ qua khoảng trắng thừa', () => {
    expect(initialsOf('  nguyễn   văn  an  ')).toBe('NV');
  });

  it('chữ cái có dấu, ký tự ngoài BMP không bị cắt đôi, rỗng thành ?', () => {
    expect(initialsOf('đặng ánh')).toBe('ĐÁ');
    expect(initialsOf('😀 Smile')).toBe('😀S');
    expect(initialsOf('')).toBe('?');
    expect(initialsOf('   ')).toBe('?');
  });
});

function serveUser(extra: Parameters<typeof serve>[0] = {}) {
  serve({ 'GET /api/v1/users/me': me, 'GET /api/v1/urls': () => ok(pageOf([])), ...extra });
}

describe('avatar ở thanh trên của trang công khai', () => {
  it('đã đăng nhập: hiện avatar chữ viết tắt, KHÔNG còn nút Link của tôi, nút đăng xuất rời rạc hay nút đăng nhập/đăng ký', async () => {
    serveUser();
    renderApp('/', { authed: true });

    const avatar = await screen.findByRole('button', { name: 'Menu tài khoản' });

    expect(avatar).toHaveTextContent('AN');
    expect(screen.queryByRole('link', { name: /Link của tôi/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Đăng xuất' })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Đăng nhập' })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Đăng ký miễn phí' })).not.toBeInTheDocument();
  });

  it('chưa đăng nhập: không có avatar, có nút đăng nhập và đăng ký', () => {
    renderApp('/');

    expect(screen.queryByRole('button', { name: 'Menu tài khoản' })).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Đăng nhập' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Đăng ký miễn phí' })).toBeInTheDocument();
  });

  it('menu hiện tên, email và ba mục: Link của tôi, Cài đặt, Đăng xuất', async () => {
    serveUser();
    renderApp('/', { authed: true });

    await userEvent.click(await screen.findByRole('button', { name: 'Menu tài khoản' }));

    const menu = within(screen.getByRole('menu'));
    expect(menu.getByText('Alice Nguyen')).toBeInTheDocument();
    expect(menu.getByText('alice@example.com')).toBeInTheDocument();
    expect(menu.getAllByRole('menuitem').map((i) => i.textContent)).toEqual(['Link của tôi', 'Cài đặt', 'Đăng xuất']);
  });

  it('mục Link của tôi đi tới danh sách link', async () => {
    serveUser();
    renderApp('/', { authed: true });

    await userEvent.click(await screen.findByRole('button', { name: 'Menu tài khoản' }));
    await userEvent.click(screen.getByRole('menuitem', { name: 'Link của tôi' }));

    expect(await screen.findByRole('heading', { name: 'Link của tôi', level: 1 })).toBeInTheDocument();
  });

  it('mục Cài đặt đi tới trang cài đặt', async () => {
    serveUser();
    renderApp('/', { authed: true });

    await userEvent.click(await screen.findByRole('button', { name: 'Menu tài khoản' }));
    await userEvent.click(screen.getByRole('menuitem', { name: 'Cài đặt' }));

    expect(await screen.findByRole('heading', { name: 'Cài đặt', level: 1 })).toBeInTheDocument();
  });

  it('cũng có trên các trang công khai khác (mở khoá, link không khả dụng)', async () => {
    serveUser();
    renderApp('/unavailable/gone', { authed: true });

    expect(await screen.findByRole('button', { name: 'Menu tài khoản' })).toBeInTheDocument();
  });

  it('chưa có họ tên thì dùng tên đăng nhập cho chữ viết tắt', async () => {
    serveUser({ 'GET /api/v1/users/me': () => ok({ ...USER, fullName: '', username: 'bachxuantrann' }) });
    renderApp('/', { authed: true });

    expect(await screen.findByRole('button', { name: 'Menu tài khoản' })).toHaveTextContent('B');
  });
});

describe('menu tài khoản trong khung ứng dụng', () => {
  it('chỉ có Cài đặt và Đăng xuất (Link của tôi đã có ở thanh bên nên không lặp)', async () => {
    serveUser();
    renderApp('/dashboard', { authed: true });
    await screen.findByRole('heading', { name: 'Link của tôi', level: 1 });

    await userEvent.click(screen.getByRole('button', { name: 'Menu tài khoản' }));

    expect(within(screen.getByRole('menu')).getAllByRole('menuitem').map((i) => i.textContent)).toEqual(['Cài đặt', 'Đăng xuất']);
  });
});
