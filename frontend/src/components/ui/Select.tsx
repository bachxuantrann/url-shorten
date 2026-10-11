import { useEffect, useId, useLayoutEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { Check, ChevronDown } from 'lucide-react';
import { placeMenu, type Placement } from './Menu';

export interface SelectOption {
  value: string;
  label: string;
}

interface SelectProps {
  value: string;
  options: SelectOption[];
  onChange: (value: string) => void;
  /** Nhãn mờ đứng trước giá trị đang chọn trên nút ("Sắp xếp:"). Cũng nằm trong tên truy cập của nút. */
  prefix?: string;
  className?: string;
}

/**
 * Bộ chọn tuỳ biến thay cho <select> gốc (danh sách gốc không style được và lạc điệu với giao diện).
 *
 * Truy cập: nút có aria-haspopup=listbox, danh sách role=listbox với các role=option (aria-selected), phần tử đang trỏ được
 * báo qua aria-activedescendant nên focus ở yên trên danh sách. Bàn phím: mũi tên/Home/End di chuyển, Enter hoặc Space chọn,
 * Esc đóng và trả focus về nút, gõ chữ để nhảy tới mục bắt đầu bằng chữ đó.
 *
 * Danh sách vẽ qua portal vào body với position: fixed (cùng lý do như Menu: không bị overflow/backdrop-filter của phần tử cha
 * cắt hay làm lệch), rộng tối thiểu bằng nút, tự lật lên khi sát đáy màn hình, đóng khi cuộn hoặc đổi cỡ cửa sổ.
 */
export function Select({ value, options, onChange, prefix, className }: SelectProps) {
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(0);
  const [pos, setPos] = useState<Placement | null>(null);
  const [minWidth, setMinWidth] = useState<number>();
  const button = useRef<HTMLButtonElement>(null);
  const list = useRef<HTMLDivElement>(null);
  const typed = useRef({ text: '', at: 0 });
  const id = useId();

  const selectedIndex = Math.max(0, options.findIndex((o) => o.value === value));
  const selected = options[selectedIndex];

  function openList() {
    setActive(selectedIndex);
    setOpen(true);
  }

  function close(returnFocus = true) {
    setOpen(false);
    if (returnFocus) button.current?.focus();
  }

  function choose(index: number) {
    const option = options[index];
    if (option) onChange(option.value);
    close();
  }

  // Đo và đặt vị trí trước khi vẽ để không nháy ở góc màn hình; đóng khi cuộn/đổi cỡ vì toạ độ cố định sẽ lệch khỏi nút
  useLayoutEffect(() => {
    if (!open) {
      setPos(null);
      return;
    }
    const b = button.current?.getBoundingClientRect();
    const l = list.current?.getBoundingClientRect();
    if (b) setMinWidth(b.width);
    if (b && l) {
      // Truyền thẳng DOMRect: top/bottom/right là getter trên prototype nên KHÔNG sao chép được bằng toán tử spread
      setPos(placeMenu(b, { width: Math.max(l.width, b.width), height: l.height }, { width: window.innerWidth, height: window.innerHeight }));
    }
    const onScrollOrResize = () => setOpen(false);
    window.addEventListener('resize', onScrollOrResize);
    window.addEventListener('scroll', onScrollOrResize, true);
    return () => {
      window.removeEventListener('resize', onScrollOrResize);
      window.removeEventListener('scroll', onScrollOrResize, true);
    };
  }, [open]);

  useEffect(() => {
    if (!open) return;
    list.current?.focus({ preventScroll: true });
    const onPointer = (e: MouseEvent) => {
      const target = e.target as Node;
      if (!list.current?.contains(target) && !button.current?.contains(target)) setOpen(false);
    };
    document.addEventListener('mousedown', onPointer);
    return () => document.removeEventListener('mousedown', onPointer);
  }, [open]);

  function onListKey(e: React.KeyboardEvent) {
    const last = options.length - 1;
    // Phím điều hướng bắt đầu lại chuỗi gõ chữ: gõ chữ sau khi dùng mũi tên/Home/End không bị ghép với chữ gõ trước đó
    if (['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(e.key)) typed.current = { text: '', at: 0 };
    switch (e.key) {
      case 'ArrowDown':
        e.preventDefault();
        setActive((a) => Math.min(a + 1, last));
        break;
      case 'ArrowUp':
        e.preventDefault();
        setActive((a) => Math.max(a - 1, 0));
        break;
      case 'Home':
        e.preventDefault();
        setActive(0);
        break;
      case 'End':
        e.preventDefault();
        setActive(last);
        break;
      case 'Enter':
      case ' ':
        e.preventDefault();
        choose(active);
        break;
      case 'Escape':
        e.preventDefault();
        close();
        break;
      case 'Tab':
        close(false); // Tab đi tiếp như bình thường, không giữ focus lại ở nút
        break;
      default:
        // Gõ chữ để nhảy tới mục bắt đầu bằng chữ đó (các phím gõ liên tiếp trong 600 ms ghép thành một chuỗi)
        if (e.key.length === 1 && !e.ctrlKey && !e.metaKey && !e.altKey) {
          const now = Date.now();
          typed.current.text = now - typed.current.at > 600 ? e.key : typed.current.text + e.key;
          typed.current.at = now;
          const needle = typed.current.text.toLowerCase();
          const hit = options.findIndex((o) => o.label.toLowerCase().startsWith(needle));
          if (hit >= 0) setActive(hit);
        }
    }
  }

  function onButtonKey(e: React.KeyboardEvent) {
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      e.preventDefault();
      openList();
    }
  }

  return (
    <>
      <button
        ref={button}
        type="button"
        className={`select-trigger${className ? ` ${className}` : ''}`}
        // Tên truy cập rõ ràng (nhãn + giá trị đang chọn): nối từ nội dung các span sẽ không có khoảng trắng ở giữa
        aria-label={prefix ? prefix + ' ' + (selected?.label ?? '') : selected?.label}
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-controls={open ? `${id}-list` : undefined}
        onClick={() => (open ? close() : openList())}
        onKeyDown={onButtonKey}
      >
        {prefix && <span className="select-prefix">{prefix}</span>}
        <span className="select-value truncate">{selected?.label}</span>
        <ChevronDown size={16} aria-hidden="true" className="select-chevron" />
      </button>
      {open &&
        createPortal(
          <div
            ref={list}
            id={`${id}-list`}
            className="select-list"
            role="listbox"
            tabIndex={-1}
            aria-activedescendant={`${id}-opt-${active}`}
            onKeyDown={onListKey}
            style={{ minWidth, ...(pos ? { top: pos.top, left: pos.left } : { top: 0, left: 0, visibility: 'hidden' as const }) }}
          >
            {options.map((option, index) => (
              <div
                key={option.value}
                id={`${id}-opt-${index}`}
                role="option"
                aria-selected={option.value === value}
                className={`select-option${index === active ? ' active' : ''}${option.value === value ? ' selected' : ''}`}
                onMouseEnter={() => setActive(index)}
                onClick={() => choose(index)}
              >
                <span className="select-check">{option.value === value && <Check size={16} aria-hidden="true" />}</span>
                {option.label}
              </div>
            ))}
          </div>,
          button.current?.closest('dialog') ?? document.body,
        )}
    </>
  );
}
