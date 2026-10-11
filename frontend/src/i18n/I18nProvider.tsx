import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { BRAND } from '../brand';
import { messages, type Language, type MessageKey } from './messages';

const STORAGE_KEY = 'lang';

function readStored(): Language | null {
  try {
    const v = localStorage.getItem(STORAGE_KEY);
    return v === 'vi' || v === 'en' ? v : null;
  } catch {
    return null;
  }
}

/** Ngôn ngữ hiện tại, đọc được ngoài React (api client gửi làm Accept-Language để lỗi từ server cùng ngôn ngữ). */
export function getLanguage(): Language {
  return readStored() ?? (navigator.language?.toLowerCase().startsWith('vi') ? 'vi' : 'en');
}

interface I18nValue {
  language: Language;
  setLanguage: (l: Language) => void;
  t: (key: MessageKey, params?: Record<string, string | number>) => string;
}

const I18nContext = createContext<I18nValue | null>(null);

export function I18nProvider({ children }: { children: ReactNode }) {
  const [language, setLang] = useState<Language>(getLanguage);

  const setLanguage = useCallback((l: Language) => {
    try {
      localStorage.setItem(STORAGE_KEY, l);
    } catch {
      /* localStorage có thể bị chặn: chỉ mất việc nhớ lựa chọn */
    }
    setLang(l);
  }, []);

  // Giữ <html lang> và mô tả trang khớp với ngôn ngữ đang dùng, kể cả ngay khi tải trang (lang tĩnh trong index.html chỉ là
  // mặc định). Trình đọc màn hình chọn giọng đọc theo lang; công cụ tìm kiếm và bản xem trước đọc description.
  useEffect(() => {
    document.documentElement.lang = language;
    let meta = document.querySelector<HTMLMetaElement>('meta[name="description"]');
    if (!meta) {
      meta = document.createElement('meta');
      meta.name = 'description';
      document.head.appendChild(meta);
    }
    meta.content = messages[language]['meta.description'].replaceAll('{brand}', BRAND.name);
  }, [language]);

  const value = useMemo<I18nValue>(
    () => ({
      language,
      setLanguage,
      t: (key, params) => {
        // {brand} luôn có sẵn; replaceAll vì một chuỗi có thể chứa cùng tham số nhiều lần
        let text = messages[language][key];
        for (const [k, v] of Object.entries({ brand: BRAND.name, ...params })) text = text.replaceAll(`{${k}}`, String(v));
        return text;
      },
    }),
    [language, setLanguage],
  );

  return <I18nContext.Provider value={value}>{children}</I18nContext.Provider>;
}

export function useI18n(): I18nValue {
  const ctx = useContext(I18nContext);
  if (!ctx) throw new Error('useI18n must be used inside I18nProvider');
  return ctx;
}
