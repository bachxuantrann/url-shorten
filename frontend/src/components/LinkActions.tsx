import { useState } from 'react';
import { Link } from 'react-router-dom';
import { BarChart3, Lock, MoreHorizontal, Pencil, Power, PowerOff, QrCode, Trash2 } from 'lucide-react';
import { toErrorView } from '../api/errors';
import type { UrlResponse } from '../api/types';
import { disableUrl, enableUrl } from '../api/urls';
import { useI18n } from '../i18n/I18nProvider';
import type { MessageKey } from '../i18n/messages';
import { DeleteLinkModal, EditLinkModal, PasswordModal, QrModal } from './modals';
import { Button } from './ui/Button';
import { Menu, MenuItem, MenuSeparator } from './ui/Menu';
import { useToast } from './ui/Toast';

type ModalKind = 'edit' | 'password' | 'qr' | 'delete';

/** Huy hiệu trạng thái: màu theo ý nghĩa (xanh = đang chạy, vàng = hết hạn, đỏ = bị chặn, xám = đã tắt). */
export function StatusBadge({ status }: { status: UrlResponse['status'] }) {
  const { t } = useI18n();
  const tone = { ACTIVE: 'badge-success', EXPIRED: 'badge-warning', BLOCKED: 'badge-danger', DISABLED: '', DELETED: '' }[status];
  return <span className={`badge ${tone}`}>{t(`link.status.${status}` as MessageKey)}</span>;
}

/**
 * Trạng thái mở hộp thoại cho một link và phần JSX các hộp thoại; dùng chung cho danh sách và trang chi tiết để hai nơi
 * cư xử giống hệt nhau. `onChanged` nhận bản mới của link, hoặc null khi link bị xoá.
 */
export function useLinkModals(onChanged: (updated: UrlResponse | null, from: UrlResponse) => void) {
  const [state, setState] = useState<{ kind: ModalKind; link: UrlResponse } | null>(null);
  const close = () => setState(null);
  const forKind = (kind: ModalKind) => (state?.kind === kind ? state.link : null);
  const handle = (updated: UrlResponse | null) => state && onChanged(updated, state.link);

  const modals = (
    <>
      <EditLinkModal link={forKind('edit')} onClose={close} onChanged={handle} />
      <PasswordModal link={forKind('password')} onClose={close} onChanged={handle} />
      <QrModal link={forKind('qr')} onClose={close} />
      <DeleteLinkModal link={forKind('delete')} onClose={close} onChanged={handle} />
    </>
  );

  return { open: (kind: ModalKind, link: UrlResponse) => setState({ kind, link }), modals };
}

interface LinkMenuProps {
  link: UrlResponse;
  open: (kind: ModalKind, link: UrlResponse) => void;
  onChanged: (updated: UrlResponse | null, from: UrlResponse) => void;
  /** Ẩn mục "Thống kê" (đang ở chính trang thống kê). */
  hideStats?: boolean;
}

/** Menu ⋯ của một link. Link bị chặn chỉ xem được thống kê và QR (backend cấm chủ link sửa/tắt/xoá). */
export function LinkMenu({ link, open, onChanged, hideStats }: LinkMenuProps) {
  const { t } = useI18n();
  const toast = useToast();
  const blocked = link.status === 'BLOCKED';
  const canToggle = link.status === 'ACTIVE' || link.status === 'DISABLED';

  async function toggle() {
    try {
      const updated = link.status === 'ACTIVE' ? await disableUrl(link.shortCode) : await enableUrl(link.shortCode);
      toast.success(t(link.status === 'ACTIVE' ? 'toast.disabled' : 'toast.enabled'));
      onChanged(updated, link);
    } catch (e) {
      toast.error(toErrorView(e, t).message);
    }
  }

  return (
    <Menu
      trigger={(props) => (
        <Button variant="ghost" size="sm" icon aria-label={t('act.more')} title={t('act.more')} {...props}>
          <MoreHorizontal size={18} aria-hidden="true" />
        </Button>
      )}
    >
      {(close) => (
        <>
          {!hideStats && (
            <Link to={`/links/${encodeURIComponent(link.shortCode)}`} className="menu-item" role="menuitem" onClick={close}>
              <BarChart3 size={16} aria-hidden="true" />
              {t('act.stats')}
            </Link>
          )}
          <MenuItem icon={<QrCode size={16} aria-hidden="true" />} onSelect={() => { close(); open('qr', link); }}>
            {t('act.qr')}
          </MenuItem>
          {!blocked && (
            <>
              <MenuItem icon={<Pencil size={16} aria-hidden="true" />} onSelect={() => { close(); open('edit', link); }}>
                {t('act.edit')}
              </MenuItem>
              <MenuItem icon={<Lock size={16} aria-hidden="true" />} onSelect={() => { close(); open('password', link); }}>
                {t('act.password')}
              </MenuItem>
              {canToggle && (
                <MenuItem
                  icon={link.status === 'ACTIVE' ? <PowerOff size={16} aria-hidden="true" /> : <Power size={16} aria-hidden="true" />}
                  onSelect={() => { close(); void toggle(); }}
                >
                  {link.status === 'ACTIVE' ? t('act.disable') : t('act.enable')}
                </MenuItem>
              )}
              <MenuSeparator />
              <MenuItem danger icon={<Trash2 size={16} aria-hidden="true" />} onSelect={() => { close(); open('delete', link); }}>
                {t('act.delete')}
              </MenuItem>
            </>
          )}
        </>
      )}
    </Menu>
  );
}
