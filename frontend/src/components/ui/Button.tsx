import { forwardRef, type ButtonHTMLAttributes, type ReactNode } from 'react';

interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: 'primary' | 'secondary' | 'ghost' | 'danger';
  size?: 'sm' | 'md' | 'lg';
  /** Chỉ có icon (cần aria-label). */
  icon?: boolean;
  block?: boolean;
  /** Đang xử lý: khoá nút và hiện vòng quay, nhưng giữ nguyên chiều rộng. */
  loading?: boolean;
  children?: ReactNode;
}

export function buttonClass({ variant = 'primary', size = 'md', icon, block }: Pick<ButtonProps, 'variant' | 'size' | 'icon' | 'block'>) {
  return [
    'btn',
    variant !== 'primary' && `btn-${variant}`,
    size !== 'md' && `btn-${size}`,
    icon && 'btn-icon',
    block && 'btn-block',
  ]
    .filter(Boolean)
    .join(' ');
}

/** Nút chuẩn của ứng dụng; mặc định type="button" để không vô tình gửi form. */
export const Button = forwardRef<HTMLButtonElement, ButtonProps>(function Button(
  { variant, size, icon, block, loading, disabled, className, children, type = 'button', ...rest },
  ref,
) {
  return (
    <button
      ref={ref}
      type={type}
      className={[buttonClass({ variant, size, icon, block }), className].filter(Boolean).join(' ')}
      disabled={disabled || loading}
      aria-busy={loading || undefined}
      {...rest}
    >
      {loading ? <span className="spinner" aria-hidden="true" /> : null}
      {children}
    </button>
  );
});
