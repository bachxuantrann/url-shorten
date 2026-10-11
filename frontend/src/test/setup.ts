import '@testing-library/jest-dom/vitest';
import { afterEach } from 'vitest';
import { cleanup, configure } from '@testing-library/react';

// Trang chi tiết được tải theo nhu cầu (lazy) nên lần đầu có thể chậm khi máy bận: 1 giây mặc định gây test chập chờn
configure({ asyncUtilTimeout: 5000 });

// jsdom chưa cài đặt showModal/close của <dialog>: mô phỏng đủ để Modal mở/đóng và hiện nội dung trong test
if (typeof HTMLDialogElement !== 'undefined' && !HTMLDialogElement.prototype.showModal) {
  HTMLDialogElement.prototype.showModal = function showModal(this: HTMLDialogElement) {
    this.setAttribute('open', '');
  };
  HTMLDialogElement.prototype.close = function close(this: HTMLDialogElement) {
    this.removeAttribute('open');
    this.dispatchEvent(new Event('close'));
  };
}

afterEach(() => {
  cleanup();
  localStorage.clear();
  document.documentElement.removeAttribute('data-theme');
});
