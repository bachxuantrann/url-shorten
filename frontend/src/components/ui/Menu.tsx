import { useEffect, useLayoutEffect, useRef, useState, type ReactNode } from 'react';
import { createPortal } from 'react-dom';

interface MenuProps {
  /** Nút mở menu; nhận props cần gắn (aria, onClick) và trạng thái mở. */
  trigger: (props: { onClick: () => void; 'aria-haspopup': 'menu'; 'aria-expanded': boolean; ref: React.Ref<HTMLButtonElement> }) => ReactNode;
  children: (close: () => void) => ReactNode;
}

export interface Placement {
  top: number;
  left: number;
}

/**
 * Vị trí (toạ độ viewport) của menu cố định bên dưới nút, căn mép phải với nút. Tràn đáy màn hình thì lật lên trên nút; không đủ
 * chỗ cả hai phía thì ép vào trong viewport. Luôn giữ cách mép {@code margin} px. Hàm thuần để test được.
 */
export function placeMenu(
  trigger: { top: number; bottom: number; right: number },
  size: { width: number; height: number },
  viewport: { width: number; height: number },
  gap = 6,
  margin = 8,
): Placement {
  const left = Math.max(margin, Math.min(trigger.right - size.width, viewport.width - size.width - margin));
  let top = trigger.bottom + gap;
  if (top + size.height > viewport.height - margin) {
    const above = trigger.top - gap - size.height;
    top = above >= margin ? above : Math.max(margin, viewport.height - size.height - margin);
  }
  return { top, left };
}

/**
 * Menu thả xuống: đóng khi bấm ra ngoài hoặc Esc (trả focus về nút), mũi tên lên/xuống di chuyển giữa các mục.
 *
 * Danh sách dùng position: fixed theo toạ độ của nút nên KHÔNG bị cắt bởi vùng cuộn/overflow của phần tử cha (bảng, thẻ, hộp
 * thoại) và tự lật lên khi sát đáy màn hình. Đổi lại phải đóng khi cuộn hoặc đổi cỡ cửa sổ, vì toạ độ cố định sẽ lệch khỏi nút.
 *
 * Danh sách được vẽ qua PORTAL vào document.body (hoặc vào <dialog> chứa nút, vì phần tử nằm trong top layer của dialog sẽ che
 * mọi thứ ngoài nó). Bắt buộc: phần tử có backdrop-filter, transform, filter... (thanh trên của chúng ta có backdrop-filter) trở thành
 * khối chứa của con position: fixed, nên nếu danh sách nằm trong thanh trên thì toạ độ viewport bị cộng lệch và menu văng ra
 * ngoài màn hình.
 */
export function Menu({ trigger, children }: MenuProps) {
  const [open, setOpen] = useState(false);
  const [pos, setPos] = useState<Placement | null>(null);
  const root = useRef<HTMLDivElement>(null);
  const list = useRef<HTMLDivElement>(null);
  const button = useRef<HTMLButtonElement>(null);

  // Đo và đặt vị trí trước khi trình duyệt vẽ để không nháy menu ở góc màn hình
  useLayoutEffect(() => {
    if (!open) {
      setPos(null);
      return;
    }
    const b = button.current?.getBoundingClientRect();
    const l = list.current?.getBoundingClientRect();
    if (b && l) setPos(placeMenu(b, { width: l.width, height: l.height }, { width: window.innerWidth, height: window.innerHeight }));
    const close = () => setOpen(false);
    window.addEventListener('resize', close);
    window.addEventListener('scroll', close, true);
    return () => {
      window.removeEventListener('resize', close);
      window.removeEventListener('scroll', close, true);
    };
  }, [open]);

  useEffect(() => {
    if (!open) return;
    const onPointer = (e: MouseEvent) => {
      const target = e.target as Node;
      if (!root.current?.contains(target) && !list.current?.contains(target)) setOpen(false);
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        setOpen(false);
        button.current?.focus();
      }
    };
    document.addEventListener('mousedown', onPointer);
    document.addEventListener('keydown', onKey);
    // Đưa focus vào mục đầu tiên để dùng được bằng bàn phím ngay
    // preventScroll: focus làm trang cuộn sẽ kích hoạt việc đóng menu ngay lập tức
    list.current?.querySelector<HTMLElement>('[role="menuitem"]')?.focus({ preventScroll: true });
    return () => {
      document.removeEventListener('mousedown', onPointer);
      document.removeEventListener('keydown', onKey);
    };
  }, [open]);

  function onListKey(e: React.KeyboardEvent) {
    if (e.key !== 'ArrowDown' && e.key !== 'ArrowUp') return;
    e.preventDefault();
    const items = [...(list.current?.querySelectorAll<HTMLElement>('[role="menuitem"]') ?? [])];
    const index = items.indexOf(document.activeElement as HTMLElement);
    const next = e.key === 'ArrowDown' ? (index + 1) % items.length : (index - 1 + items.length) % items.length;
    items[next]?.focus({ preventScroll: true });
  }

  return (
    <div className="menu" ref={root}>
      {trigger({ onClick: () => setOpen((o) => !o), 'aria-haspopup': 'menu', 'aria-expanded': open, ref: button })}
      {open &&
        createPortal(
          <div
            ref={list}
            className="menu-list"
            role="menu"
            onKeyDown={onListKey}
            style={pos ? { top: pos.top, left: pos.left } : { top: 0, left: 0, visibility: 'hidden' }}
          >
            {children(() => setOpen(false))}
          </div>,
          button.current?.closest('dialog') ?? document.body,
        )}
    </div>
  );
}

interface ItemProps {
  onSelect: () => void;
  icon?: ReactNode;
  danger?: boolean;
  children: ReactNode;
}

export function MenuItem({ onSelect, icon, danger, children }: ItemProps) {
  return (
    <button type="button" role="menuitem" className={`menu-item${danger ? ' danger' : ''}`} onClick={onSelect}>
      {icon}
      {children}
    </button>
  );
}

export function MenuSeparator() {
  return <div className="menu-sep" role="separator" />;
}
