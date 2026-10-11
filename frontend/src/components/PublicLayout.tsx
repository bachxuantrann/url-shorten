import { Link, Outlet, useLocation } from 'react-router-dom';
import { BRAND } from '../brand';
import { useAuth } from '../auth/AuthProvider';
import { useI18n } from '../i18n/I18nProvider';
import { Preferences } from './Preferences';
import { buttonClass } from './ui/Button';
import { Logo } from './ui/Logo';
import { UserMenu } from './UserMenu';

/** Khung cho trang công khai (trang chủ, mở khoá, link không khả dụng): thanh trên + nội dung + chân trang. */
export function PublicLayout() {
  const { t } = useI18n();
  const { user } = useAuth();
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
              // Đã đăng nhập: avatar chữ viết tắt + menu tài khoản (thay cho nút Link của tôi và nút đăng xuất rời rạc)
              <UserMenu showLinks />
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
