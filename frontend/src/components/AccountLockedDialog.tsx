import { useNavigate } from 'react-router-dom';
import { ShieldAlert } from 'lucide-react';
import { useAuth } from '../auth/AuthProvider';
import { useI18n } from '../i18n/I18nProvider';
import { Button } from './ui/Button';
import { Modal } from './ui/Modal';

/**
 * Hộp thoại hiện khi admin vừa khoá tài khoản đang đăng nhập: phiên đã bị xoá nên người dùng sẽ bị đưa ra trang đăng nhập,
 * nhưng phải được nói rõ vì sao (trước đây bị đá ra âm thầm, tưởng là lỗi). Nằm ở gốc ứng dụng nên hiện trên mọi trang.
 */
export function AccountLockedDialog() {
  const { t } = useI18n();
  const { accountLocked, dismissAccountLocked } = useAuth();
  const navigate = useNavigate();

  function close() {
    dismissAccountLocked();
    navigate('/login', { replace: true });
  }

  return (
    <Modal open={accountLocked} onClose={close} title={t('locked.title')}>
      <div className="form">
        <div className="row" style={{ gap: 12, alignItems: 'flex-start', flexWrap: 'nowrap' }}>
          <span className="status-icon danger" style={{ margin: 0, width: 44, height: 44, flex: 'none' }}>
            <ShieldAlert size={22} aria-hidden="true" />
          </span>
          <p className="muted">{t('locked.text')}</p>
        </div>
        <div className="modal-foot" style={{ padding: 0 }}>
          <Button onClick={close}>{t('locked.ok')}</Button>
        </div>
      </div>
    </Modal>
  );
}
