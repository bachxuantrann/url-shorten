import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';

export type ThemeMode = 'light' | 'dark' | 'system';
const STORAGE_KEY = 'theme';

function readStored(): ThemeMode {
  try {
    const v = localStorage.getItem(STORAGE_KEY);
    return v === 'light' || v === 'dark' ? v : 'system';
  } catch {
    return 'system';
  }
}

/** Áp chế độ lên <html>: system = bỏ thuộc tính để CSS theo prefers-color-scheme của hệ điều hành. */
function apply(mode: ThemeMode) {
  const root = document.documentElement;
  if (mode === 'system') root.removeAttribute('data-theme');
  else root.setAttribute('data-theme', mode);
}

interface ThemeValue {
  mode: ThemeMode;
  setMode: (m: ThemeMode) => void;
}

const ThemeContext = createContext<ThemeValue | null>(null);

export function ThemeProvider({ children }: { children: ReactNode }) {
  const [mode, setModeState] = useState<ThemeMode>(readStored);

  useEffect(() => apply(mode), [mode]);

  const setMode = useCallback((m: ThemeMode) => {
    try {
      if (m === 'system') localStorage.removeItem(STORAGE_KEY);
      else localStorage.setItem(STORAGE_KEY, m);
    } catch {
      /* bị chặn: chỉ mất việc nhớ lựa chọn */
    }
    setModeState(m);
  }, []);

  const value = useMemo(() => ({ mode, setMode }), [mode, setMode]);
  return <ThemeContext.Provider value={value}>{children}</ThemeContext.Provider>;
}

export function useTheme(): ThemeValue {
  const ctx = useContext(ThemeContext);
  if (!ctx) throw new Error('useTheme must be used inside ThemeProvider');
  return ctx;
}
