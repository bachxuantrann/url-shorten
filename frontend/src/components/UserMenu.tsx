import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Link2, LogOut, Settings } from 'lucide-react';
import { useAuth } from '../auth/AuthProvider';
import { useI18n } from '../i18n/I18nProvider';
import { LogoutDialog } from './LogoutDialog';
import { Menu, MenuItem, MenuSeparator } from './ui/Menu';

/** Chữ viết tắt cho avatar: chữ cái đầu của tối đa hai từ đầu tiên, viết hoa ("Trần Xuân Bách" -> "TX"). Rỗng -> "?". */
export function initialsOf(name: string): string {
  const letters = name
    .trim()
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((word) => Array.from(word)[0]!.toUpperCase()) // Array.from: không cắt đôi ký tự ngoài BMP (emoji, chữ hiếm)
    .join('');
  return letters || '?';
}

/**
 * Avatar chữ viết tắt của người dùng đã đăng nhập, bấm mở menu tài khoản (tên + email, Link của tôi, Cài đặt, Đăng xuất có xác
 * nhận). Dùng chung cho thanh trên của khung ứng dụng và của trang công khai để người dùng thấy cùng một thứ ở mọi nơi.
 * `showLinks`: thêm mục "Link của tôi" (ở trang công khai không có thanh bên để vào danh sách).
 */
export function UserMenu({ showLinks = false }: { showLinks?: boolean }) {
  const { t } = useI18n();
  const { user } = useAuth();
  const navigate = useNavigate();
  const [logoutOpen, setLogoutOpen] = useState(false);

  const displayName = user?.fullName || user?.username || '';

  return (
    <>
      <Menu
        trigger={(props) => (
          <button type="button" className="avatar" aria-label={t('nav.account')} {...props}>
            {initialsOf(displayName)}
          </button>
        )}
      >
        {(close) => (
          <>
            <div style={{ padding: '6px 10px 8px' }}>
              <div style={{ fontWeight: 650 }} className="truncate">
                {displayName}
              </div>
              <div className="muted small truncate">{user?.email}</div>
            </div>
            <MenuSeparator />
            {showLinks && (
              <MenuItem
                icon={<Link2 size={16} aria-hidden="true" />}
                onSelect={() => {
                  close();
                  navigate('/dashboard');
                }}
              >
                {t('nav.links')}
              </MenuItem>
            )}
            <MenuItem
              icon={<Settings size={16} aria-hidden="true" />}
              onSelect={() => {
                close();
                navigate('/settings');
              }}
            >
              {t('nav.settings')}
            </MenuItem>
            <MenuItem
              icon={<LogOut size={16} aria-hidden="true" />}
              onSelect={() => {
                close();
                setLogoutOpen(true);
              }}
            >
              {t('nav.logout')}
            </MenuItem>
          </>
        )}
      </Menu>
      <LogoutDialog open={logoutOpen} onClose={() => setLogoutOpen(false)} />
    </>
  );
}
