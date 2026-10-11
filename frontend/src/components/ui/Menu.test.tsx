import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { Menu, MenuItem, placeMenu } from './Menu';

const viewport = { width: 1000, height: 700 };
const size = { width: 190, height: 160 };

describe('placeMenu (hàm thuần)', () => {
  it('mặc định thả xuống dưới nút, căn mép phải với nút', () => {
    expect(placeMenu({ top: 100, bottom: 136, right: 800 }, size, viewport)).toEqual({ top: 142, left: 610 });
  });

  it('sát đáy màn hình thì lật lên phía trên nút', () => {
    const { top } = placeMenu({ top: 600, bottom: 636, right: 800 }, size, viewport);

    expect(top).toBe(600 - 6 - 160); // ngay trên nút
  });

  it('không đủ chỗ ở cả hai phía thì ép vào trong viewport thay vì tràn ra ngoài', () => {
    const { top } = placeMenu({ top: 80, bottom: 116, right: 800 }, { width: 190, height: 700 }, { width: 1000, height: 720 });

    expect(top).toBeGreaterThanOrEqual(8);
    expect(top + 700).toBeLessThanOrEqual(720 - 8 + 0.0001);
  });

  it('nút sát mép trái: không để menu tràn khỏi mép trái', () => {
    expect(placeMenu({ top: 10, bottom: 46, right: 40 }, size, viewport).left).toBe(8);
  });

  it('nút sát mép phải: không để menu tràn khỏi mép phải', () => {
    const { left } = placeMenu({ top: 10, bottom: 46, right: 1400 }, size, viewport);

    expect(left + size.width).toBeLessThanOrEqual(viewport.width - 8);
  });
});

function Demo() {
  return (
    // Bọc trong vùng cuộn: đúng hoàn cảnh gây lỗi cắt menu ở bảng danh sách link
    <div style={{ overflow: 'auto', height: 40 }}>
      <Menu
        trigger={(props) => (
          <button type="button" {...props}>
            mở
          </button>
        )}
      >
        {(close) => (
          <>
            <MenuItem onSelect={close}>Một</MenuItem>
            <MenuItem onSelect={close}>Hai</MenuItem>
          </>
        )}
      </Menu>
    </div>
  );
}

function mockRects(button: Partial<DOMRect>, list: { width: number; height: number }) {
  vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(function (this: HTMLElement) {
    if (this.getAttribute('role') === 'menu') return { width: list.width, height: list.height, top: 0, left: 0, right: list.width, bottom: list.height } as DOMRect;
    if (this.tagName === 'BUTTON' && this.textContent === 'mở') return { top: 10, bottom: 46, left: 700, right: 736, width: 36, height: 36, ...button } as DOMRect;
    return { top: 0, bottom: 0, left: 0, right: 0, width: 0, height: 0 } as DOMRect;
  });
}

beforeEach(() => {
  Object.defineProperty(window, 'innerWidth', { value: 1000, configurable: true });
  Object.defineProperty(window, 'innerHeight', { value: 700, configurable: true });
});
afterEach(() => vi.restoreAllMocks());

describe('Menu trong component', () => {
  it('đặt danh sách theo toạ độ viewport của nút (không phụ thuộc phần tử cha cuộn) và hiện ra', async () => {
    mockRects({}, { width: 190, height: 80 });
    render(<Demo />);

    await userEvent.click(screen.getByRole('button', { name: 'mở' }));

    const menu = screen.getByRole('menu');
    expect(menu.style.top).toBe('52px'); // bottom 46 + gap 6
    expect(menu.style.left).toBe('546px'); // right 736 - width 190
    expect(menu.style.visibility).toBe('');
  });

  it('nút sát đáy thì menu lật lên trên', async () => {
    mockRects({ top: 640, bottom: 676, right: 736 }, { width: 190, height: 80 });
    render(<Demo />);

    await userEvent.click(screen.getByRole('button', { name: 'mở' }));

    expect(screen.getByRole('menu').style.top).toBe(`${640 - 6 - 80}px`);
  });

  it('đóng khi trang cuộn hoặc đổi cỡ cửa sổ (toạ độ cố định sẽ lệch khỏi nút)', async () => {
    mockRects({}, { width: 190, height: 80 });
    render(<Demo />);

    await userEvent.click(screen.getByRole('button', { name: 'mở' }));
    window.dispatchEvent(new Event('scroll'));
    await vi.waitFor(() => expect(screen.queryByRole('menu')).not.toBeInTheDocument());

    await userEvent.click(screen.getByRole('button', { name: 'mở' }));
    expect(screen.getByRole('menu')).toBeInTheDocument();
    window.dispatchEvent(new Event('resize'));
    await vi.waitFor(() => expect(screen.queryByRole('menu')).not.toBeInTheDocument());
  });

  it('mở menu không tự kích hoạt cuộn làm menu đóng ngay (focus dùng preventScroll)', async () => {
    mockRects({}, { width: 190, height: 80 });
    const focus = vi.spyOn(HTMLElement.prototype, 'focus');
    render(<Demo />);

    await userEvent.click(screen.getByRole('button', { name: 'mở' }));

    expect(screen.getByRole('menu')).toBeInTheDocument();
    expect(focus).toHaveBeenCalledWith({ preventScroll: true });
  });

  it('chọn một mục đóng menu; Esc đóng và trả focus về nút', async () => {
    mockRects({}, { width: 190, height: 80 });
    render(<Demo />);
    const trigger = screen.getByRole('button', { name: 'mở' });

    await userEvent.click(trigger);
    await userEvent.click(screen.getByRole('menuitem', { name: 'Một' }));
    expect(screen.queryByRole('menu')).not.toBeInTheDocument();

    await userEvent.click(trigger);
    await userEvent.keyboard('{Escape}');
    expect(screen.queryByRole('menu')).not.toBeInTheDocument();
    expect(trigger).toHaveFocus();
  });
});

describe('Menu vẽ qua portal (tránh khối chứa của phần tử có backdrop-filter)', () => {
  // Thanh trên có backdrop-filter: nó biến thành khối chứa của con position: fixed nên menu nằm TRONG thanh trên bị lệch toạ độ và
  // văng khỏi màn hình (lỗi thật đã gặp: bấm theme/ngôn ngữ/tài khoản không thấy menu). jsdom không có bố cục nên test cấu trúc DOM.
  it('danh sách menu là con trực tiếp của body, không nằm trong phần tử bọc của nút', async () => {
    mockRects({}, { width: 190, height: 80 });
    render(
      <header style={{ backdropFilter: 'blur(10px)' }}>
        <Demo />
      </header>,
    );

    await userEvent.click(screen.getByRole('button', { name: 'mở' }));

    const menu = screen.getByRole('menu');
    expect(menu.parentElement).toBe(document.body);
    expect(menu.closest('header')).toBeNull();
    expect(menu.closest('.menu')).toBeNull();
  });

  it('nút nằm trong <dialog> thì menu được vẽ trong chính dialog đó (top layer che mọi thứ ngoài nó)', async () => {
    mockRects({}, { width: 190, height: 80 });
    render(
      <dialog open>
        <Demo />
      </dialog>,
    );

    await userEvent.click(screen.getByRole('button', { name: 'mở' }));

    expect(screen.getByRole('menu').parentElement?.tagName).toBe('DIALOG');
  });

  it('bấm vào mục trong portal vẫn chọn được và đóng menu; bấm ra ngoài thì đóng', async () => {
    mockRects({}, { width: 190, height: 80 });
    render(<Demo />);

    await userEvent.click(screen.getByRole('button', { name: 'mở' }));
    await userEvent.click(screen.getByRole('menuitem', { name: 'Hai' }));
    expect(screen.queryByRole('menu')).not.toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'mở' }));
    await userEvent.click(document.body);
    expect(screen.queryByRole('menu')).not.toBeInTheDocument();
  });

  it('bấm vào chính danh sách (không phải một mục) không làm menu đóng', async () => {
    mockRects({}, { width: 190, height: 80 });
    render(<Demo />);

    await userEvent.click(screen.getByRole('button', { name: 'mở' }));
    await userEvent.click(screen.getByRole('menu'));

    expect(screen.getByRole('menu')).toBeInTheDocument();
  });
});

describe('hợp đồng CSS', () => {
  it('danh sách menu dùng position: fixed để không bị overflow của bảng/thẻ cha cắt', () => {
    const css = readFileSync(resolve(__dirname, '../../styles.css'), 'utf8');
    const rule = /\.menu-list \{([^}]*)\}/.exec(css)?.[1] ?? '';

    expect(rule).toContain('position: fixed');
    expect(rule).not.toMatch(/position:\s*absolute/);
  });
});
