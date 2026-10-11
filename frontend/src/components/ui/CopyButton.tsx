import { useState } from 'react';
import { Check, Copy } from 'lucide-react';
import { useI18n } from '../../i18n/I18nProvider';
import { Button } from './Button';
import { useToast } from './Toast';

interface CopyButtonProps {
  text: string;
  /** Hiện chữ cạnh icon (nút to ở trang chủ) thay vì chỉ icon. */
  label?: boolean;
  size?: 'sm' | 'md' | 'lg';
  variant?: 'primary' | 'secondary' | 'ghost';
}

/** Chép vào clipboard, đổi icon thành dấu tick 2 giây và báo bằng toast; lỗi clipboard (bị chặn) không làm vỡ giao diện. */
export function CopyButton({ text, label, size = 'sm', variant = 'ghost' }: CopyButtonProps) {
  const { t } = useI18n();
  const toast = useToast();
  const [copied, setCopied] = useState(false);

  async function copy() {
    try {
      await navigator.clipboard.writeText(text);
      setCopied(true);
      toast.success(t('toast.copied'));
      setTimeout(() => setCopied(false), 2000);
    } catch {
      /* clipboard bị chặn: người dùng vẫn chọn và chép tay được */
    }
  }

  const Icon = copied ? Check : Copy;
  const caption = copied ? t('common.copied') : t('common.copy');
  return (
    <Button variant={variant} size={size} icon={!label} onClick={copy} aria-label={label ? undefined : caption} title={label ? undefined : caption}>
      <Icon size={size === 'lg' ? 20 : 16} aria-hidden="true" />
      {label && caption}
    </Button>
  );
}
