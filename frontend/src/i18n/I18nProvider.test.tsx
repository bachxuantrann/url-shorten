import { act, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { BRAND } from '../brand';
import { I18nProvider, useI18n } from './I18nProvider';
import { messages } from './messages';

function Switcher() {
  const { language, setLanguage } = useI18n();
  return (
    <button type="button" onClick={() => setLanguage(language === 'vi' ? 'en' : 'vi')}>
      {language}
    </button>
  );
}

const description = () => document.querySelector<HTMLMetaElement>('meta[name="description"]')?.content;

beforeEach(() => {
  document.documentElement.lang = 'vi'; // giá trị tĩnh của index.html
  document.querySelectorAll('meta[name="description"]').forEach((m) => m.remove());
});
afterEach(() => vi.unstubAllGlobals());

describe('<html lang> và mô tả trang', () => {
  it('đặt lang theo ngôn ngữ đã lưu ngay khi tải trang, không đợi người dùng đổi', () => {
    localStorage.setItem('lang', 'en');

    render(<I18nProvider><Switcher /></I18nProvider>);

    expect(document.documentElement.lang).toBe('en');
  });

  it('khi chưa lưu gì thì theo ngôn ngữ trình duyệt (tiếng Việt)', () => {
    vi.stubGlobal('navigator', { ...navigator, language: 'vi-VN' });

    render(<I18nProvider><Switcher /></I18nProvider>);

    expect(document.documentElement.lang).toBe('vi');
  });

  it('khi chưa lưu gì và trình duyệt không phải tiếng Việt thì dùng tiếng Anh', () => {
    vi.stubGlobal('navigator', { ...navigator, language: 'fr-FR' });

    render(<I18nProvider><Switcher /></I18nProvider>);

    expect(document.documentElement.lang).toBe('en');
  });

  it('đổi ngôn ngữ cập nhật lang và mô tả trang theo bản dịch, kèm tên sản phẩm', async () => {
    localStorage.setItem('lang', 'vi');
    render(<I18nProvider><Switcher /></I18nProvider>);
    expect(description()).toBe(messages.vi['meta.description'].replaceAll('{brand}', BRAND.name));
    expect(description()).toContain(BRAND.name);

    await act(async () => screen.getByRole('button').click());

    expect(document.documentElement.lang).toBe('en');
    expect(description()).toBe(messages.en['meta.description'].replaceAll('{brand}', BRAND.name));
    expect(description()).not.toContain('{brand}');
  });

  it('tự tạo thẻ meta description nếu trang chưa có, và không tạo trùng khi đổi ngôn ngữ', async () => {
    render(<I18nProvider><Switcher /></I18nProvider>);
    await act(async () => screen.getByRole('button').click());
    await act(async () => screen.getByRole('button').click());

    expect(document.querySelectorAll('meta[name="description"]')).toHaveLength(1);
  });
});
