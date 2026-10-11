import { Link, Outlet, useLocation, useNavigate } from 'react-router-dom';
import { LayoutDashboard, LogOut } from 'lucide-react';
import { BRAND } from '../brand';
import { useAuth } from '../auth/AuthProvider';
import { useI18n } from '../i18n/I18nProvider';
import { Preferences } from './Preferences';
import { buttonClass } from './ui/Button';
import { Logo } from './ui/Logo';

/** Khung cho trang công khai (trang chủ, mở khoá, link không khả dụng): thanh trên + nội dung + chân trang. */
export function PublicLayout() {
  const { t } = useI18n();
  const { user, logout } = useAuth();
  const navigate = useNavigate();
  const onHome = useLocation().pathname === '/';

  return (
    <>
      <a href="#main" className="skip-link">
        {t('skip')}
      </a>
      <header className="topbar">
        <div className="topbar-inner">
          <Logo />
          {onHome && (
            <nav className="nav" aria-label="Main">
              <a href="#features">{t('nav.features')}</a>
              <a href="#how">{t('nav.how')}</a>
              <a href="#faq">{t('nav.faq')}</a>
            </nav>
          )}
          <div className="topbar-actions">
            <Preferences />
            {user ? (
              <>
                <Link to="/dashboard" className={buttonClass({ size: 'md' })}>
                  <LayoutDashboard size={16} aria-hidden="true" />
                  <span className="hide-sm">{t('nav.links')}</span>
                </Link>
                <button
                  type="button"
                  className={buttonClass({ variant: 'ghost', icon: true })}
                  aria-label={t('nav.logout')}
                  title={t('nav.logout')}
                  onClick={async () => {
                    await logout();
                    navigate('/');
                  }}
                >
                  <LogOut size={18} aria-hidden="true" />
                </button>
              </>
            ) : (
              <>
                <Link to="/login" className={`${buttonClass({ variant: 'ghost' })} hide-sm`}>
                  {t('nav.login')}
                </Link>
                <Link to="/register" className={buttonClass({})}>
                  {t('nav.signup')}
                </Link>
              </>
            )}
          </div>
        </div>
      </header>
      <main id="main">
        <Outlet />
      </main>
      <footer className="footer">
        <div className="page footer-inner">
          <Logo />
          <span>{t('footer.tagline')}</span>
          <span>{t('footer.rights', { year: new Date().getFullYear(), brand: BRAND.name })}</span>
        </div>
      </footer>
    </>
  );
}
