import { lazy, type ComponentType } from 'react';

const RELOAD_FLAG = 'chunk-reload';

/** Lỗi tải một module/chunk động (điển hình: sau khi deploy bản mới, tên file băm cũ không còn nữa). */
export function isChunkError(error: unknown): boolean {
  const text = `${(error as Error | undefined)?.name ?? ''} ${(error as Error | undefined)?.message ?? ''}`;
  return /Failed to fetch dynamically imported module|error loading dynamically imported module|Importing a module script failed|ChunkLoadError|Loading chunk .* failed/i.test(
    text,
  );
}

/**
 * React.lazy có khả năng tự hồi phục sau deploy: nếu chunk không tải được, tải lại trang MỘT lần để lấy index.html mới
 * (trỏ tới chunk mới). Cờ trong sessionStorage chặn vòng lặp tải lại vô hạn khi lỗi là thật (mất mạng, server hỏng): lần
 * thứ hai lỗi được ném ra để ErrorBoundary hiện. Tải thành công thì xoá cờ để lần deploy sau lại được tự phục hồi.
 *
 * Cần thiết vì React.lazy nhớ vĩnh viễn kết quả thất bại: nút "Thử lại" không cứu được, chỉ tải lại trang mới được.
 */
// eslint-disable-next-line @typescript-eslint/no-explicit-any -- cùng chữ ký với React.lazy (props của component bất kỳ)
export function lazyWithReload<T extends ComponentType<any>>(factory: () => Promise<{ default: T }>) {
  return lazy(async () => {
    try {
      const module = await factory();
      clearFlag();
      return module;
    } catch (error) {
      if (isChunkError(error) && claimReload()) {
        window.location.reload();
        // Không resolve trong lúc trang đang tải lại: tránh nháy ra trang lỗi
        return new Promise<never>(() => {});
      }
      throw error;
    }
  });
}

/**
 * Giành quyền tải lại trang: chỉ true nếu chưa tải lại lần nào VÀ ghi được cờ. Không ghi được (sessionStorage bị chặn) thì
 * trả false: không có cờ để chặn vòng lặp nên thà hiện trang lỗi còn hơn tải lại vô hạn.
 */
function claimReload(): boolean {
  try {
    if (sessionStorage.getItem(RELOAD_FLAG) !== null) return false;
    sessionStorage.setItem(RELOAD_FLAG, '1');
    return true;
  } catch {
    return false;
  }
}

function clearFlag() {
  try {
    sessionStorage.removeItem(RELOAD_FLAG);
  } catch {
    /* bị chặn: không có gì để xoá */
  }
}
