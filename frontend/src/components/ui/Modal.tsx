import { useEffect, useRef, type ReactNode } from 'react';
import { X } from 'lucide-react';
import { useI18n } from '../../i18n/I18nProvider';

interface ModalProps {
  open: boolean;
  onClose: () => void;
  title: string;
  description?: string;
  children: ReactNode;
  /** Hàng nút ở chân modal. */
  footer?: ReactNode;
}

/**
 * Hộp thoại dựa trên phần tử <dialog> gốc: trình duyệt lo bẫy focus, phím Esc và lớp nền, nên truy cập được bằng bàn phím
 * mà không cần thư viện. Nội dung chỉ được dựng khi mở để form luôn bắt đầu từ trạng thái sạch.
 */
export function Modal({ open, onClose, title, description, children, footer }: ModalProps) {
  const { t } = useI18n();
  const ref = useRef<HTMLDialogElement>(null);

  useEffect(() => {
    const dialog = ref.current;
    if (!dialog) return;
    if (open && !dialog.open) {
      dialog.showModal();
      // autoFocus của React chạy lúc <dialog> chưa mở nên không có tác dụng, còn showModal() đưa focus tới nút "Đóng":
      // chuyển focus tới ô nhập đầu tiên của form để người dùng gõ được ngay (hộp thoại xác nhận không có ô nhập thì giữ nguyên)
      dialog
        .querySelector<HTMLElement>('.modal-body input:not([type="hidden"]):not([disabled]), .modal-body textarea:not([disabled]), .modal-body select:not([disabled])')
        ?.focus();
    }
    if (!open && dialog.open) dialog.close();
  }, [open]);

  return (
    <dialog
      ref={ref}
      className="modal"
      aria-labelledby="modal-title"
      onClose={onClose}
      // Bấm vào lớp nền (chính phần tử dialog, không phải nội dung bên trong) thì đóng
      onClick={(e) => {
        if (e.target === e.currentTarget) onClose();
      }}
    >
      {open && (
        <>
          <div className="modal-head">
            <div>
              <h2 id="modal-title">{title}</h2>
              {description && <p className="muted small" style={{ marginTop: 4 }}>{description}</p>}
            </div>
            <button type="button" className="btn btn-ghost btn-icon btn-sm" aria-label={t('common.close')} onClick={onClose}>
              <X size={18} aria-hidden="true" />
            </button>
          </div>
          <div className="modal-body">{children}</div>
          {footer && <div className="modal-foot">{footer}</div>}
        </>
      )}
    </dialog>
  );
}
