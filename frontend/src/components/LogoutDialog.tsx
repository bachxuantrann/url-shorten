import { useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthProvider';
import { useI18n } from '../i18n/I18nProvider';
import { useSubmit } from '../hooks/useSubmit';
import { Button } from './ui/Button';
import { Modal } from './ui/Modal';

/**
 * Hộp thoại xác nhận đăng xuất. Đăng xuất là thao tác có hệ quả (mất phiên, phải nhập lại mật khẩu) và nút nằm sát các nút
 * thường dùng, nên không đăng xuất ngay khi bấm.
 */
export function LogoutDialog({ open, onClose }: { open: boolean; onClose: () => void }) {
  const { t } = useI18n();
  const { logout } = useAuth();
  const navigate = useNavigate();
  const { busy, run } = useSubmit();

  async function confirm() {
    // Về trang chủ TRƯỚC khi xoá phiên: xoá trước thì RequireAuth đẩy người dùng sang /login
    navigate('/');
    await run(logout);
    onClose();
  }

  return (
    <Modal open={open} onClose={onClose} title={t('logout.title')}>
      <p className="muted">{t('logout.text')}</p>
      <div className="modal-foot" style={{ padding: '16px 0 0' }}>
        <Button variant="secondary" onClick={onClose} disabled={busy}>
          {t('common.cancel')}
        </Button>
        <Button loading={busy} onClick={confirm} autoFocus>
          {t('nav.logout')}
        </Button>
      </div>
    </Modal>
  );
}
