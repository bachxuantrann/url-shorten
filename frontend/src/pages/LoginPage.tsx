import { useState, type FormEvent } from 'react';
import { Link, Navigate, useLocation, useNavigate } from 'react-router-dom';
import { AlertCircle, Lock, User } from 'lucide-react';
import { toErrorView, type ErrorView } from '../api/errors';
import { useAuth } from '../auth/AuthProvider';
import { AuthLayout } from '../components/AuthLayout';
import { Field } from '../components/Field';
import { Button } from '../components/ui/Button';
import { useDocumentTitle } from '../hooks/useDocumentTitle';
import { useI18n } from '../i18n/I18nProvider';

export function LoginPage() {
  const { t } = useI18n();
  useDocumentTitle(t('nav.login'));
  const { user, login } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ErrorView | null>(null);

  // Quay lại đúng trang đang định vào trước khi bị đưa sang đăng nhập
  const from = (location.state as { from?: string } | null)?.from ?? '/dashboard';

  if (user) return <Navigate to={from} replace />;

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await login(username.trim(), password);
      navigate(from, { replace: true });
    } catch (err) {
      setError(toErrorView(err, t));
      setBusy(false);
    }
  }

  return (
    <AuthLayout title={t('auth.login.title')} subtitle={t('auth.login.sub')}>
      <form className="form" onSubmit={submit} noValidate>
        {error && (
          <div className="alert" role="alert">
            <AlertCircle size={18} aria-hidden="true" />
            <span>{error.message}</span>
          </div>
        )}
        <Field
          label={t('auth.username')}
          name="username"
          autoComplete="username"
          icon={<User size={18} aria-hidden="true" />}
          value={username}
          onChange={(e) => setUsername(e.target.value)}
          error={error?.fields.username}
          autoFocus
        />
        <Field
          label={t('auth.password')}
          name="password"
          type="password"
          autoComplete="current-password"
          icon={<Lock size={18} aria-hidden="true" />}
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          error={error?.fields.password}
        />
        <Button type="submit" size="lg" block loading={busy} disabled={username === '' || password === ''}>
          {t('auth.login.submit')}
        </Button>
      </form>
      <p className="muted" style={{ marginTop: 24, textAlign: 'center' }}>
        {t('auth.login.noAccount')} <Link to="/register">{t('nav.signup')}</Link>
      </p>
    </AuthLayout>
  );
}
