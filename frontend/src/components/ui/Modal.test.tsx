import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { beforeEach, describe, expect, it } from 'vitest';
import { I18nProvider } from '../../i18n/I18nProvider';
import { Modal } from './Modal';

function Harness({ children }: { children: React.ReactNode }) {
  const [open, setOpen] = useState(false);
  return (
    <>
      <button onClick={() => setOpen(true)}>mở</button>
      <Modal open={open} onClose={() => setOpen(false)} title="Tiêu đề">
        {children}
      </Modal>
    </>
  );
}

const renderModal = (children: React.ReactNode) =>
  render(
    <I18nProvider>
      <Harness>{children}</Harness>
    </I18nProvider>,
  );

describe('Modal', () => {
  // Tiếng Việt như các test khác để tìm nút theo nhãn ổn định (mặc định theo ngôn ngữ trình duyệt)
  beforeEach(() => localStorage.setItem('lang', 'vi'));

  it('khi mở thì đưa focus tới ô nhập đầu tiên (không để ở nút Đóng) để gõ được ngay', async () => {
    renderModal(
      <form>
        <input aria-label="đích" />
        <input aria-label="hạn" />
      </form>,
    );

    await userEvent.click(screen.getByRole('button', { name: 'mở' }));

    expect(screen.getByLabelText('đích')).toHaveFocus();
  });

  it('bỏ qua ô ẩn hoặc bị khoá và tìm cả textarea', async () => {
    renderModal(
      <form>
        <input type="hidden" name="h" />
        <input aria-label="khoá" disabled />
        <textarea aria-label="ghi chú" />
      </form>,
    );

    await userEvent.click(screen.getByRole('button', { name: 'mở' }));

    expect(screen.getByLabelText('ghi chú')).toHaveFocus();
  });

  it('hộp thoại xác nhận không có ô nhập thì không ép focus đi đâu cả', async () => {
    renderModal(<p>Bạn chắc chứ?</p>);

    await userEvent.click(screen.getByRole('button', { name: 'mở' }));

    expect(screen.getByText('Bạn chắc chứ?')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Đóng' })).not.toHaveAttribute('disabled');
  });

  it('mở lại lần nữa vẫn focus vào ô nhập đầu tiên', async () => {
    renderModal(<input aria-label="đích" />);

    await userEvent.click(screen.getByRole('button', { name: 'mở' }));
    await userEvent.click(screen.getByRole('button', { name: 'Đóng' }));
    await userEvent.click(screen.getByRole('button', { name: 'mở' }));

    expect(screen.getByLabelText('đích')).toHaveFocus();
  });
});
