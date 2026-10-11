import { createContext, useCallback, useContext, useMemo, useRef, useState, type ReactNode } from 'react';
import { CheckCircle2, X, XCircle } from 'lucide-react';
import { useI18n } from '../../i18n/I18nProvider';

interface ToastItem {
  id: number;
  message: string;
  kind: 'success' | 'error';
}

interface ToastApi {
  success: (message: string) => void;
  error: (message: string) => void;
}

const ToastContext = createContext<ToastApi | null>(null);
const DURATION_MS = 4000;

/** Thông báo ngắn góc dưới phải: tự biến mất sau vài giây, đọc được bằng trình đọc màn hình (role=status). */
export function ToastProvider({ children }: { children: ReactNode }) {
  const { t } = useI18n();
  const [items, setItems] = useState<ToastItem[]>([]);
  const nextId = useRef(1);

  const dismiss = useCallback((id: number) => setItems((list) => list.filter((i) => i.id !== id)), []);

  const push = useCallback(
    (kind: ToastItem['kind'], message: string) => {
      const id = nextId.current++;
      setItems((list) => [...list.slice(-3), { id, message, kind }]);
      setTimeout(() => dismiss(id), DURATION_MS);
    },
    [dismiss],
  );

  const api = useMemo<ToastApi>(() => ({ success: (m) => push('success', m), error: (m) => push('error', m) }), [push]);

  return (
    <ToastContext.Provider value={api}>
      {children}
      <div className="toasts" role="status" aria-live="polite">
        {items.map((item) => (
          <div key={item.id} className={`toast ${item.kind === 'error' ? 'error' : ''}`}>
            {item.kind === 'error' ? <XCircle size={18} aria-hidden="true" /> : <CheckCircle2 size={18} aria-hidden="true" />}
            <span>{item.message}</span>
            <button type="button" aria-label={t('toast.dismiss')} onClick={() => dismiss(item.id)}>
              <X size={16} aria-hidden="true" />
            </button>
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  );
}

export function useToast(): ToastApi {
  const ctx = useContext(ToastContext);
  if (!ctx) throw new Error('useToast must be used inside ToastProvider');
  return ctx;
}
