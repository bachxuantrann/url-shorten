import { useEffect, useRef, useState, type ReactNode } from 'react';

interface MenuProps {
  /** Nút mở menu; nhận props cần gắn (aria, onClick) và trạng thái mở. */
  trigger: (props: { onClick: () => void; 'aria-haspopup': 'menu'; 'aria-expanded': boolean; ref: React.Ref<HTMLButtonElement> }) => ReactNode;
  children: (close: () => void) => ReactNode;
  /** Mở lên trên (menu ở sát đáy màn hình). */
  up?: boolean;
}

/** Menu thả xuống: đóng khi bấm ra ngoài hoặc Esc (trả focus về nút), mũi tên lên/xuống di chuyển giữa các mục. */
export function Menu({ trigger, children, up }: MenuProps) {
  const [open, setOpen] = useState(false);
  const root = useRef<HTMLDivElement>(null);
  const button = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    if (!open) return;
    const onPointer = (e: MouseEvent) => {
      if (!root.current?.contains(e.target as Node)) setOpen(false);
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
    root.current?.querySelector<HTMLElement>('[role="menuitem"]')?.focus();
    return () => {
      document.removeEventListener('mousedown', onPointer);
      document.removeEventListener('keydown', onKey);
    };
  }, [open]);

  function onListKey(e: React.KeyboardEvent) {
    if (e.key !== 'ArrowDown' && e.key !== 'ArrowUp') return;
    e.preventDefault();
    const items = [...(root.current?.querySelectorAll<HTMLElement>('[role="menuitem"]') ?? [])];
    const index = items.indexOf(document.activeElement as HTMLElement);
    const next = e.key === 'ArrowDown' ? (index + 1) % items.length : (index - 1 + items.length) % items.length;
    items[next]?.focus();
  }

  return (
    <div className="menu" ref={root}>
      {trigger({ onClick: () => setOpen((o) => !o), 'aria-haspopup': 'menu', 'aria-expanded': open, ref: button })}
      {open && (
        <div className={`menu-list${up ? ' up' : ''}`} role="menu" onKeyDown={onListKey}>
          {children(() => setOpen(false))}
        </div>
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
