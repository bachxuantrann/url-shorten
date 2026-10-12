import { createContext, useCallback, useContext, useMemo, useState } from 'react';
import { Link, NavLink, Outlet } from 'react-router-dom';
import { BarChart3, CircleHelp, Flag, Globe, Layers, Link2, Menu as MenuIcon, MessageSquareText, Plus, Settings, Users } from 'lucide-react';
import { isAdmin, useAuth } from '../auth/AuthProvider';
import { useI18n } from '../i18n/I18nProvider';
import { CreateLinkModal } from './modals';
import { Preferences } from './Preferences';
import { Button } from './ui/Button';
import { Logo } from './ui/Logo';
import { UserMenu } from './UserMenu';

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
  const [navOpen, setNavOpen] = useState(false);
  const [createOpen, setCreateOpen] = useState(false);
  const [createdVersion, setCreatedVersion] = useState(0);

  const openCreate = useCallback(() => setCreateOpen(true), []);
  const ctx = useMemo(() => ({ openCreate, createdVersion }), [openCreate, createdVersion]);

  const { user } = useAuth();
  const navItems = [
    { to: '/overview', icon: BarChart3, label: t('nav.overview') },
    { to: '/dashboard', icon: Link2, label: t('nav.links') },
    { to: '/settings', icon: Settings, label: t('nav.settings') },
  ];
  // Mục quản trị chỉ hiện cho admin (chỉ để điều hướng: mọi API admin vẫn tự kiểm tra quyền ở server)
  const adminItems = isAdmin(user)
    ? [
        { to: '/admin/links', icon: Layers, label: t('nav.admin.links') },
        { to: '/admin/reports', icon: Flag, label: t('nav.admin.reports') },
        { to: '/admin/appeals', icon: MessageSquareText, label: t('nav.admin.appeals') },
        { to: '/admin/domains', icon: Globe, label: t('nav.admin.domains') },
        { to: '/admin/users', icon: Users, label: t('nav.admin.users') },
      ]
    : [];

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
            {adminItems.length > 0 && (
              <>
                <div className="side-heading">{t('nav.admin')}</div>
                {adminItems.map(({ to, icon: Icon, label }) => (
                  <NavLink key={to} to={to} className={({ isActive }) => (isActive ? 'active' : undefined)}>
                    <Icon size={18} aria-hidden="true" />
                    {label}
                  </NavLink>
                ))}
              </>
            )}
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
            <UserMenu />
          </header>
          <main id="main" className="shell-content">
            <Outlet />
          </main>
        </div>
      </div>

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
