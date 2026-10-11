import type { ReactNode } from 'react';
import { BarChart3, QrCode, ShieldCheck } from 'lucide-react';
import { useI18n } from '../i18n/I18nProvider';
import { Preferences } from './Preferences';
import { Logo } from './ui/Logo';

interface AuthLayoutProps {
  title: string;
  subtitle: string;
  children: ReactNode;
}

/** Khung đăng nhập/đăng ký: form bên trái, bảng giới thiệu bên phải (ẩn trên màn hình hẹp). */
export function AuthLayout({ title, subtitle, children }: AuthLayoutProps) {
  const { t } = useI18n();
  const points = [
    { icon: BarChart3, title: t('auth.aside.1.t'), text: t('auth.aside.1.d') },
    { icon: ShieldCheck, title: t('auth.aside.2.t'), text: t('auth.aside.2.d') },
    { icon: QrCode, title: t('auth.aside.3.t'), text: t('auth.aside.3.d') },
  ];

  return (
    <div className="auth">
      <div className="auth-form-side">
        <div className="auth-top">
          <Logo />
          <div className="row" style={{ gap: 4 }}>
            <Preferences />
          </div>
        </div>
        <main id="main" className="auth-form-wrap">
          <h1>{title}</h1>
          <p className="sub">{subtitle}</p>
          {children}
        </main>
      </div>
      <aside className="auth-aside" aria-hidden="true">
        <h2>{t('auth.aside.title')}</h2>
        <ul className="auth-points">
          {points.map(({ icon: Icon, title: pointTitle, text }) => (
            <li key={pointTitle}>
              <span className="icon-box">
                <Icon size={20} />
              </span>
              <div>
                <strong>{pointTitle}</strong>
                <br />
                <span>{text}</span>
              </div>
            </li>
          ))}
        </ul>
      </aside>
    </div>
  );
}
