import { afterEach, describe, expect, it, vi } from 'vitest';
import { copyText } from './clipboard';

function setClipboard(value: unknown) {
  Object.defineProperty(navigator, 'clipboard', { value, configurable: true });
}

function setExecCommand(fn: ((cmd: string) => boolean) | undefined) {
  Object.defineProperty(document, 'execCommand', { value: fn, configurable: true, writable: true });
}

afterEach(() => {
  setClipboard(undefined);
  setExecCommand(undefined);
});

describe('copyText', () => {
  it('dùng clipboard API khi có', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    setClipboard({ writeText });
    const exec = vi.fn();
    setExecCommand(exec);

    expect(await copyText('https://sho.rt/abc')).toBe(true);
    expect(writeText).toHaveBeenCalledWith('https://sho.rt/abc');
    expect(exec).not.toHaveBeenCalled();
  });

  it('clipboard API bị từ chối thì dùng cách dự phòng execCommand và vẫn thành công', async () => {
    setClipboard({ writeText: vi.fn().mockRejectedValue(new DOMException('denied', 'NotAllowedError')) });
    let copied = '';
    setExecCommand((cmd) => {
      copied = (document.querySelector('textarea') as HTMLTextAreaElement).value;
      return cmd === 'copy';
    });

    expect(await copyText('https://sho.rt/abc')).toBe(true);
    expect(copied).toBe('https://sho.rt/abc');
  });

  it('không có clipboard API (trang HTTP) cũng dùng cách dự phòng', async () => {
    setClipboard(undefined);
    setExecCommand(() => true);

    expect(await copyText('x')).toBe(true);
  });

  it('cả hai cách đều thất bại thì trả false, không ném lỗi', async () => {
    setClipboard({ writeText: vi.fn().mockRejectedValue(new Error('denied')) });
    setExecCommand(() => false);
    expect(await copyText('x')).toBe(false);

    setExecCommand(() => {
      throw new Error('boom');
    });
    expect(await copyText('x')).toBe(false);

    setExecCommand(undefined);
    expect(await copyText('x')).toBe(false);
  });

  it('dọn ô văn bản tạm khỏi trang sau khi chép, kể cả khi thất bại', async () => {
    setClipboard(undefined);
    setExecCommand(() => true);
    await copyText('x');
    setExecCommand(() => {
      throw new Error('boom');
    });
    await copyText('x');

    expect(document.querySelectorAll('textarea')).toHaveLength(0);
  });

  it('trả focus về phần tử đang focus trước đó', async () => {
    const button = document.createElement('button');
    document.body.appendChild(button);
    button.focus();
    setClipboard(undefined);
    setExecCommand(() => true);

    await copyText('x');

    expect(document.activeElement).toBe(button);
    button.remove();
  });
});
