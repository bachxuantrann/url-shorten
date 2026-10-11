import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { I18nProvider } from '../i18n/I18nProvider';
import { ErrorBoundary } from './ErrorBoundary';

let shouldThrow = true;

function Bomb() {
  if (shouldThrow) throw new Error('boom');
  return <p>trang bình thường</p>;
}

function renderBoundary() {
  return render(
    <I18nProvider>
      <ErrorBoundary>
        <Bomb />
      </ErrorBoundary>
    </I18nProvider>,
  );
}

const reload = vi.fn();
let consoleError: ReturnType<typeof vi.spyOn>;

beforeEach(() => {
  localStorage.setItem('lang', 'vi');
  shouldThrow = true;
  reload.mockReset();
  vi.stubGlobal('location', { ...window.location, reload });
  // React và boundary đều ghi lỗi ra console: giữ lại để assert rằng lỗi KHÔNG bị nuốt
  consoleError = vi.spyOn(console, 'error').mockImplementation(() => {});
});
afterEach(() => {
  consoleError.mockRestore();
  vi.unstubAllGlobals();
});

describe('ErrorBoundary', () => {
  it('không can thiệp khi mọi thứ bình thường', () => {
    shouldThrow = false;

    renderBoundary();

    expect(screen.getByText('trang bình thường')).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('component lỗi lúc render thì hiện trang báo lỗi thay vì trang trắng', () => {
    renderBoundary();

    expect(screen.getByRole('alert')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Đã xảy ra lỗi' })).toBeInTheDocument();
    expect(screen.getByText(/vừa có bản mới/)).toBeInTheDocument();
    expect(screen.queryByText('trang bình thường')).not.toBeInTheDocument();
  });

  it('lỗi vẫn được ghi ra console để điều tra, không bị nuốt', () => {
    renderBoundary();

    expect(consoleError).toHaveBeenCalledWith('Unhandled UI error', expect.objectContaining({ message: 'boom' }), expect.any(String));
  });

  it('nút Tải lại trang tải lại hẳn trang', async () => {
    renderBoundary();

    await userEvent.click(screen.getByRole('button', { name: 'Tải lại trang' }));

    expect(reload).toHaveBeenCalledOnce();
  });

  it('nút Thử lại dựng lại nội dung: hết lỗi thì quay về trang bình thường', async () => {
    renderBoundary();
    shouldThrow = false;

    await userEvent.click(screen.getByRole('button', { name: 'Thử lại' }));

    expect(screen.getByText('trang bình thường')).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('Thử lại mà vẫn lỗi thì tiếp tục hiện trang báo lỗi (không lặp vô hạn)', async () => {
    renderBoundary();

    await userEvent.click(screen.getByRole('button', { name: 'Thử lại' }));

    expect(screen.getByRole('alert')).toBeInTheDocument();
  });

  it('có lối về trang chủ bằng tải lại hẳn (router có thể đang hỏng) và bản tiếng Anh', () => {
    localStorage.setItem('lang', 'en');

    renderBoundary();

    expect(screen.getByRole('heading', { name: 'Something went wrong' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Go to homepage' })).toHaveAttribute('href', '/');
  });
});
