import { useId, useState, type InputHTMLAttributes, type ReactNode } from 'react';
import { Eye, EyeOff } from 'lucide-react';
import { useI18n } from '../i18n/I18nProvider';

interface FieldProps extends InputHTMLAttributes<HTMLInputElement> {
  label: string;
  error?: string;
  hint?: string;
  /** Icon bên trái trong ô nhập. */
  icon?: ReactNode;
  /** Ẩn nhãn nhưng vẫn giữ cho trình đọc màn hình. */
  hideLabel?: boolean;
  /** Nội dung chèn sau nhãn (ví dụ "không bắt buộc"). */
  labelSuffix?: ReactNode;
}

/**
 * Ô nhập có nhãn, gợi ý và lỗi theo field từ server. Ô mật khẩu có nút hiện/ẩn. Lỗi và gợi ý được gắn vào ô bằng
 * aria-describedby để trình đọc màn hình đọc cùng ô.
 */
export function Field({ label, error, hint, icon, hideLabel, labelSuffix, id, type, className, ...input }: FieldProps) {
  const { t } = useI18n();
  const autoId = useId();
  const inputId = id ?? autoId;
  const describedBy = [error ? `${inputId}-err` : null, hint ? `${inputId}-hint` : null].filter(Boolean).join(' ') || undefined;
  const [revealed, setRevealed] = useState(false);
  const isPassword = type === 'password';

  return (
    <div className="field">
      <label htmlFor={inputId} className={hideLabel ? 'sr-only' : undefined}>
        {label}
        {labelSuffix}
      </label>
      <div className="input-wrap">
        {icon && <span className="input-icon">{icon}</span>}
        <input
          id={inputId}
          type={isPassword && revealed ? 'text' : type}
          className={['input', icon && 'with-icon', isPassword && 'with-trailing', className].filter(Boolean).join(' ')}
          aria-invalid={error ? true : undefined}
          aria-describedby={describedBy}
          {...input}
        />
        {isPassword && (
          <button
            type="button"
            className="btn btn-ghost btn-icon btn-sm input-trailing"
            aria-label={revealed ? t('auth.hide') : t('auth.show')}
            aria-pressed={revealed}
            onClick={() => setRevealed((r) => !r)}
          >
            {revealed ? <EyeOff size={18} aria-hidden="true" /> : <Eye size={18} aria-hidden="true" />}
          </button>
        )}
      </div>
      {error ? (
        <span id={`${inputId}-err`} className="field-error" role="alert">
          {error}
        </span>
      ) : (
        hint && (
          <span id={`${inputId}-hint`} className="field-hint">
            {hint}
          </span>
        )
      )}
    </div>
  );
}
