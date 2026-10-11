import { render, screen } from '@testing-library/react';
import { Suspense } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ErrorBoundary } from '../components/ErrorBoundary';
import { I18nProvider } from '../i18n/I18nProvider';
import { isChunkError, lazyWithReload } from './lazyWithReload';

const reload = vi.fn();
let consoleError: ReturnType<typeof vi.spyOn>;

const chunkError = () => new TypeError('Failed to fetch dynamically imported module: https://x/assets/Detail-abc.js');

function Page() {
  return <p>trang chi tiết</p>;
}

function renderLazy(factory: () => Promise<{ default: () => React.ReactElement }>) {
  const Lazy = lazyWithReload(factory);
  return render(
    <I18nProvider>
      <ErrorBoundary>
        <Suspense fallback={<p>đang tải</p>}>
          <Lazy />
        </Suspense>
      </ErrorBoundary>
    </I18nProvider>,
  );
}

beforeEach(() => {
  localStorage.setItem('lang', 'vi');
  sessionStorage.clear();
  reload.mockReset();
  vi.stubGlobal('location', { ...window.location, reload });
  consoleError = vi.spyOn(console, 'error').mockImplementation(() => {});
});
afterEach(() => {
  consoleError.mockRestore();
  vi.unstubAllGlobals();
});

describe('isChunkError', () => {
  it('nhận diện lỗi tải module động của các trình duyệt phổ biến', () => {
    expect(isChunkError(chunkError())).toBe(true);
    expect(isChunkError(new TypeError('error loading dynamically imported module'))).toBe(true);
    expect(isChunkError(new TypeError('Importing a module script failed.'))).toBe(true);
    const named = new Error('x');
    named.name = 'ChunkLoadError';
    expect(isChunkError(named)).toBe(true);
    expect(isChunkError(new Error('Loading chunk 7 failed.'))).toBe(true);
  });

  it('không nhầm lỗi thường với lỗi chunk', () => {
    expect(isChunkError(new Error('boom'))).toBe(false);
    expect(isChunkError(new TypeError("Cannot read properties of undefined (reading 'x')"))).toBe(false);
    expect(isChunkError(undefined)).toBe(false);
    expect(isChunkError(null)).toBe(false);
  });
});

describe('lazyWithReload', () => {
  it('tải thành công thì hiện trang và xoá cờ tải lại', async () => {
    sessionStorage.setItem('chunk-reload', '1');

    renderLazy(async () => ({ default: Page }));

    expect(await screen.findByText('trang chi tiết')).toBeInTheDocument();
    expect(sessionStorage.getItem('chunk-reload')).toBeNull();
    expect(reload).not.toHaveBeenCalled();
  });

  it('chunk lỗi lần đầu: tự tải lại trang đúng một lần, không nháy ra trang lỗi', async () => {
    renderLazy(() => Promise.reject(chunkError()));

    await vi.waitFor(() => expect(reload).toHaveBeenCalledOnce());
    expect(sessionStorage.getItem('chunk-reload')).toBe('1');
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(screen.getByText('đang tải')).toBeInTheDocument();
  });

  it('đã tải lại một lần mà vẫn lỗi (lỗi thật) thì hiện trang lỗi, không lặp vô hạn', async () => {
    sessionStorage.setItem('chunk-reload', '1');

    renderLazy(() => Promise.reject(chunkError()));

    expect(await screen.findByRole('alert')).toBeInTheDocument();
    expect(reload).not.toHaveBeenCalled();
  });

  it('lỗi chunk: trang lỗi không có nút Thử lại vô dụng, chỉ có Tải lại trang', async () => {
    sessionStorage.setItem('chunk-reload', '1');

    renderLazy(() => Promise.reject(chunkError()));

    await screen.findByRole('alert');
    expect(screen.getByRole('button', { name: 'Tải lại trang' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Thử lại' })).not.toBeInTheDocument();
  });

  it('lỗi không phải chunk thì không tải lại trang và vẫn có nút Thử lại', async () => {
    renderLazy(() => Promise.reject(new Error('boom')));

    await screen.findByRole('alert');
    expect(reload).not.toHaveBeenCalled();
    expect(screen.getByRole('button', { name: 'Thử lại' })).toBeInTheDocument();
  });

  it('sessionStorage bị chặn: KHÔNG tải lại (không có cờ chặn vòng lặp) mà hiện trang lỗi', async () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('blocked');
    });
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('blocked');
    });

    renderLazy(() => Promise.reject(chunkError()));

    expect(await screen.findByRole('alert')).toBeInTheDocument();
    expect(reload).not.toHaveBeenCalled();
    vi.restoreAllMocks();
  });
});
