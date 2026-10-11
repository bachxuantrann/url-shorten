import { createContext, useCallback, useContext, useMemo, useState } from 'react';
import { Link, NavLink, Outlet, useNavigate } from 'react-router-dom';
import { CircleHelp, Link2, LogOut, Menu as MenuIcon, Plus, Settings } from 'lucide-react';
import { useAuth } from '../auth/AuthProvider';
import { useI18n } from '../i18n/I18nProvider';
import { LogoutDialog } from './LogoutDialog';
import { CreateLinkModal } from './modals';
import { Preferences } from './Preferences';
import { Button } from './ui/Button';
import { Logo } from './ui/Logo';
import { Menu, MenuItem, MenuSeparator } from './ui/Menu';

interface CreateLinkContext {
  /** Mở hộp thoại tạo link (nút ở thanh trên, trang danh sách, trạng thái rỗng). */
  openCreate: () => void;
  /** Tăng sau mỗi lần tạo thành công để các trang đang hiển thị danh sách tải lại. */
  createdVersion: number;
}

const CreateContext = createContext<CreateLinkContext | null>(null);

export function useCreateLink(): CreateLinkContext {
  const ctx = useContext(CreateContext);
  if (!ctx) throw new Error('useCreateLink must be used inside AppLayout');
  return ctx;
}

/** Khung ứng dụng sau đăng nhập: thanh bên (thu thành ngăn kéo trên điện thoại), thanh trên và hộp thoại tạo link. */
export function AppLayout() {
  const { t } = useI18n();
  const { user } = useAuth();
  const navigate = useNavigate();
  const [navOpen, setNavOpen] = useState(false);
  const [createOpen, setCreateOpen] = useState(false);
  const [logoutOpen, setLogoutOpen] = useState(false);
  const [createdVersion, setCreatedVersion] = useState(0);

  const openCreate = useCallback(() => setCreateOpen(true), []);
  const ctx = useMemo(() => ({ openCreate, createdVersion }), [openCreate, createdVersion]);

  const displayName = user?.fullName || user?.username || '';
  const initials = displayName
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((w) => w[0]!.toUpperCase())
    .join('');

  const navItems = [
    { to: '/dashboard', icon: Link2, label: t('nav.links') },
    { to: '/settings', icon: Settings, label: t('nav.settings') },
  ];

  return (
    <CreateContext.Provider value={ctx}>
      <a href="#main" className="skip-link">
        {t('skip')}
      </a>
      <div className="shell">
        <aside className={`sidebar${navOpen ? ' open' : ''}`}>
          <Logo to="/dashboard" />
          <nav className="side-nav" aria-label="Main" onClick={() => setNavOpen(false)}>
            {navItems.map(({ to, icon: Icon, label }) => (
              <NavLink key={to} to={to} className={({ isActive }) => (isActive ? 'active' : undefined)}>
                <Icon size={18} aria-hidden="true" />
                {label}
              </NavLink>
            ))}
          </nav>
          {/* Trợ giúp thay cho nút "Trang chủ" trước đây: người đang quản lý link cần câu trả lời nhanh, không cần quay về trang giới thiệu */}
          <div className="side-foot side-nav" onClick={() => setNavOpen(false)}>
            <Link to="/#faq">
              <CircleHelp size={18} aria-hidden="true" />
              {t('nav.help')}
            </Link>
          </div>
        </aside>
        <div className={`scrim${navOpen ? ' open' : ''}`} onClick={() => setNavOpen(false)} aria-hidden="true" />

        <div className="shell-main">
          <header className="shell-top">
            <Button variant="ghost" icon className="menu-toggle" aria-label={t('nav.menu')} onClick={() => setNavOpen(true)}>
              <MenuIcon size={20} aria-hidden="true" />
            </Button>
            <span className="spacer" />
            <Button onClick={openCreate}>
              <Plus size={18} aria-hidden="true" />
              {t('links.create')}
            </Button>
            <Preferences />
            <Menu
              trigger={(props) => (
                <button type="button" className="avatar" aria-label={t('nav.account')} {...props}>
                  {initials || '?'}
                </button>
              )}
            >
              {(close) => (
                <>
                  <div style={{ padding: '6px 10px 8px' }}>
                    <div style={{ fontWeight: 650 }} className="truncate">{displayName}</div>
                    <div className="muted small truncate">{user?.email}</div>
                  </div>
                  <MenuSeparator />
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
          </header>
          <main id="main" className="shell-content">
            <Outlet />
          </main>
        </div>
      </div>

      <LogoutDialog open={logoutOpen} onClose={() => setLogoutOpen(false)} />
      <CreateLinkModal
        open={createOpen}
        onClose={() => setCreateOpen(false)}
        onCreated={() => {
          setCreatedVersion((v) => v + 1);
          setCreateOpen(false);
        }}
      />
    </CreateContext.Provider>
  );
}
