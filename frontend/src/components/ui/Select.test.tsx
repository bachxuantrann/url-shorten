import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { Select, type SelectOption } from './Select';

const OPTIONS: SelectOption[] = [
  { value: 'createdAt', label: 'Mới nhất' },
  { value: 'clickCount', label: 'Nhiều click nhất' },
  { value: 'expiresAt', label: 'Ngày hết hạn' },
];

function Harness({ onChange = () => {}, initial = 'createdAt' }: { onChange?: (v: string) => void; initial?: string }) {
  const [value, setValue] = useState(initial);
  return (
    <Select
      prefix="Sắp xếp:"
      value={value}
      options={OPTIONS}
      onChange={(v) => {
        setValue(v);
        onChange(v);
      }}
    />
  );
}

function mockRects(button: Partial<DOMRect> = {}, list = { width: 200, height: 120 }) {
  vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(function (this: HTMLElement) {
    // DOMRect thật (top/bottom/right là getter trên prototype, giống trình duyệt): object thường che mất lỗi sao chép bằng spread
    if (this.getAttribute('role') === 'listbox') return new DOMRect(0, 0, list.width, list.height);
    if (this.classList.contains('select-trigger')) {
      const top = button.top ?? 10;
      const left = button.left ?? 600;
      return new DOMRect(left, top, (button.right ?? 800) - left, (button.bottom ?? 50) - top);
    }
    return new DOMRect(0, 0, 0, 0);
  });
}

const trigger = () => screen.getByRole('button', { name: /Sắp xếp/ });
const active = () => screen.getByRole('listbox').getAttribute('aria-activedescendant');
const activeLabel = () => document.getElementById(active()!)?.textContent;

beforeEach(() => {
  Object.defineProperty(window, 'innerWidth', { value: 1000, configurable: true });
  Object.defineProperty(window, 'innerHeight', { value: 700, configurable: true });
  mockRects();
});
afterEach(() => vi.restoreAllMocks());

describe('Select: hiển thị', () => {
  it('nút hiện nhãn mờ và giá trị đang chọn, tên truy cập gồm cả hai', () => {
    render(<Harness initial="clickCount" />);

    expect(screen.getByRole('button', { name: 'Sắp xếp: Nhiều click nhất' })).toHaveAttribute('aria-haspopup', 'listbox');
    expect(trigger()).toHaveAttribute('aria-expanded', 'false');
  });

  it('mở danh sách: đủ các lựa chọn, mục đang chọn có aria-selected và dấu tick', async () => {
    render(<Harness initial="clickCount" />);

    await userEvent.click(trigger());

    const options = screen.getAllByRole('option');
    expect(options.map((o) => o.textContent)).toEqual(['Mới nhất', 'Nhiều click nhất', 'Ngày hết hạn']);
    expect(options.map((o) => o.getAttribute('aria-selected'))).toEqual(['false', 'true', 'false']);
    expect(options[1].querySelector('svg')).not.toBeNull();
    expect(options[0].querySelector('svg')).toBeNull();
    expect(trigger()).toHaveAttribute('aria-expanded', 'true');
  });

  it('khi mở, mục đang chọn là mục đang trỏ tới và danh sách nhận focus', async () => {
    render(<Harness initial="expiresAt" />);

    await userEvent.click(trigger());

    expect(activeLabel()).toBe('Ngày hết hạn');
    expect(screen.getByRole('listbox')).toHaveFocus();
  });

  it('danh sách rộng tối thiểu bằng nút và nằm ngay dưới nút', async () => {
    render(<Harness />);

    await userEvent.click(trigger());

    const list = screen.getByRole('listbox');
    expect(list.style.minWidth).toBe('200px');
    expect(list.style.top).toBe('56px'); // bottom 50 + gap 6
    expect(list.style.left).toBe('600px'); // right 800 - width 200
  });

  it('sát đáy màn hình thì lật lên trên nút', async () => {
    mockRects({ top: 640, bottom: 680, left: 600, right: 800 }, { width: 200, height: 120 });
    render(<Harness />);

    await userEvent.click(trigger());

    expect(screen.getByRole('listbox').style.top).toBe(`${640 - 6 - 120}px`);
  });
});

describe('Select: chọn bằng chuột', () => {
  it('bấm một mục gọi onChange với giá trị đó, đóng danh sách, đổi chữ trên nút và trả focus về nút', async () => {
    const onChange = vi.fn();
    render(<Harness onChange={onChange} />);

    await userEvent.click(trigger());
    await userEvent.click(screen.getByRole('option', { name: 'Nhiều click nhất' }));

    expect(onChange).toHaveBeenCalledExactlyOnceWith('clickCount');
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Sắp xếp: Nhiều click nhất' })).toHaveFocus();
  });

  it('rê chuột đổi mục đang trỏ', async () => {
    render(<Harness />);
    await userEvent.click(trigger());

    await userEvent.hover(screen.getByRole('option', { name: 'Ngày hết hạn' }));

    expect(activeLabel()).toBe('Ngày hết hạn');
  });

  it('bấm nút lần nữa hoặc bấm ra ngoài thì đóng mà không đổi giá trị', async () => {
    const onChange = vi.fn();
    render(<Harness onChange={onChange} />);

    await userEvent.click(trigger());
    await userEvent.click(trigger());
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument();

    await userEvent.click(trigger());
    await userEvent.click(document.body);
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument();
    expect(onChange).not.toHaveBeenCalled();
  });

  it('đóng khi trang cuộn hoặc đổi cỡ cửa sổ (toạ độ cố định sẽ lệch khỏi nút)', async () => {
    render(<Harness />);

    await userEvent.click(trigger());
    window.dispatchEvent(new Event('scroll'));
    await vi.waitFor(() => expect(screen.queryByRole('listbox')).not.toBeInTheDocument());

    await userEvent.click(trigger());
    window.dispatchEvent(new Event('resize'));
    await vi.waitFor(() => expect(screen.queryByRole('listbox')).not.toBeInTheDocument());
  });
});

describe('Select: bàn phím', () => {
  it('mũi tên xuống trên nút mở danh sách', async () => {
    render(<Harness />);
    trigger().focus();

    await userEvent.keyboard('{ArrowDown}');

    expect(screen.getByRole('listbox')).toBeInTheDocument();
  });

  it('mũi tên lên/xuống di chuyển và dừng ở hai đầu; Enter chọn mục đang trỏ', async () => {
    const onChange = vi.fn();
    render(<Harness onChange={onChange} />);
    await userEvent.click(trigger());

    await userEvent.keyboard('{ArrowUp}');
    expect(activeLabel()).toBe('Mới nhất'); // đã ở đầu, không vòng lại
    await userEvent.keyboard('{ArrowDown}{ArrowDown}{ArrowDown}{ArrowDown}');
    expect(activeLabel()).toBe('Ngày hết hạn'); // dừng ở cuối
    await userEvent.keyboard('{ArrowUp}');
    expect(activeLabel()).toBe('Nhiều click nhất');
    await userEvent.keyboard('{Enter}');

    expect(onChange).toHaveBeenCalledExactlyOnceWith('clickCount');
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument();
  });

  it('Home, End và phím cách', async () => {
    const onChange = vi.fn();
    render(<Harness onChange={onChange} />);
    await userEvent.click(trigger());

    await userEvent.keyboard('{End}');
    expect(activeLabel()).toBe('Ngày hết hạn');
    await userEvent.keyboard('{Home}');
    expect(activeLabel()).toBe('Mới nhất');
    await userEvent.keyboard('{End} ');

    expect(onChange).toHaveBeenCalledExactlyOnceWith('expiresAt');
  });

  it('Esc đóng, không đổi giá trị và trả focus về nút', async () => {
    const onChange = vi.fn();
    render(<Harness onChange={onChange} />);
    await userEvent.click(trigger());
    await userEvent.keyboard('{ArrowDown}{Escape}');

    expect(screen.queryByRole('listbox')).not.toBeInTheDocument();
    expect(onChange).not.toHaveBeenCalled();
    expect(trigger()).toHaveFocus();
  });

  it('Tab đóng danh sách', async () => {
    render(
      <>
        <Harness />
        <button type="button">tiếp theo</button>
      </>,
    );
    await userEvent.click(trigger());

    await userEvent.keyboard('{Tab}');

    expect(screen.queryByRole('listbox')).not.toBeInTheDocument();
  });

  it('gõ chữ nhảy tới mục bắt đầu bằng chữ đó, gõ liên tiếp ghép thành chuỗi', async () => {
    render(<Harness />);
    await userEvent.click(trigger());

    await userEvent.keyboard('n');
    expect(activeLabel()).toBe('Nhiều click nhất');
    await userEvent.keyboard('{Home}');
    await userEvent.keyboard('ng');
    expect(activeLabel()).toBe('Ngày hết hạn');
  });

  it('chữ không khớp mục nào thì giữ nguyên mục đang trỏ', async () => {
    render(<Harness />);
    await userEvent.click(trigger());

    await userEvent.keyboard('z');

    expect(activeLabel()).toBe('Mới nhất');
  });
});

describe('Select: portal', () => {
  it('danh sách là con của body, không nằm trong phần tử bọc (tránh overflow và backdrop-filter của cha)', async () => {
    render(
      <header style={{ backdropFilter: 'blur(4px)', overflow: 'auto' }}>
        <Harness />
      </header>,
    );

    await userEvent.click(trigger());

    const list = screen.getByRole('listbox');
    expect(list.parentElement).toBe(document.body);
    expect(list.closest('header')).toBeNull();
  });

  it('nút trong <dialog> thì danh sách được vẽ trong dialog đó', async () => {
    render(
      <dialog open>
        <Harness />
      </dialog>,
    );

    await userEvent.click(trigger());

    expect(screen.getByRole('listbox').parentElement?.tagName).toBe('DIALOG');
  });
});
