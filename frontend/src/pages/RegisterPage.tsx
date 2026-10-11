import { useState, type FormEvent } from 'react';
import { Link, Navigate, useNavigate } from 'react-router-dom';
import { AlertCircle, Check, Circle, Lock, Mail, User } from 'lucide-react';
import { toErrorView, type ErrorView } from '../api/errors';
import { useAuth } from '../auth/AuthProvider';
import { AuthLayout } from '../components/AuthLayout';
import { Field } from '../components/Field';
import { Button } from '../components/ui/Button';
import { useToast } from '../components/ui/Toast';
import { useDocumentTitle } from '../hooks/useDocumentTitle';
import { useI18n } from '../i18n/I18nProvider';

/** Quy tắc mật khẩu khớp validation của backend (RegisterRequest): hiện tích xanh ngay khi gõ để người dùng không phải đoán. */
function passwordRules(pw: string) {
  return [
    { key: 'auth.rule.len', ok: pw.length >= 8 },
    { key: 'auth.rule.upper', ok: /[A-Z]/.test(pw) },
    { key: 'auth.rule.lower', ok: /[a-z]/.test(pw) },
    { key: 'auth.rule.digit', ok: /\d/.test(pw) },
  ] as const;
}

export function RegisterPage() {
  const { t } = useI18n();
  useDocumentTitle(t('auth.register.title'));
  const { user, register, login } = useAuth();
  const toast = useToast();
  const navigate = useNavigate();
  const [form, setForm] = useState({ fullName: '', username: '', email: '', password: '' });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ErrorView | null>(null);

  if (user) return <Navigate to="/dashboard" replace />;

  const set = (name: keyof typeof form) => (e: React.ChangeEvent<HTMLInputElement>) => setForm((f) => ({ ...f, [name]: e.target.value }));
  const rules = passwordRules(form.password);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    const payload = { ...form, fullName: form.fullName.trim(), username: form.username.trim(), email: form.email.trim() };
    try {
      await register(payload);
    } catch (err) {
      setError(toErrorView(err, t));
      setBusy(false);
      return;
    }
    // Đăng nhập luôn sau khi tạo tài khoản: người dùng vào thẳng trang của mình, không phải gõ lại thông tin
    try {
      await login(payload.username, payload.password);
      toast.success(t('auth.welcome', { name: payload.fullName }));
      navigate('/dashboard', { replace: true });
    } catch {
      navigate('/login', { replace: true });
    }
  }

  return (
    <AuthLayout title={t('auth.register.title')} subtitle={t('auth.register.sub')}>
      <form className="form" onSubmit={submit} noValidate>
        {error && (
          <div className="alert" role="alert">
            <AlertCircle size={18} aria-hidden="true" />
            <span>{error.message}</span>
          </div>
        )}
        <Field
          label={t('auth.fullName')}
          name="fullName"
          autoComplete="name"
          icon={<User size={18} aria-hidden="true" />}
          value={form.fullName}
          onChange={set('fullName')}
          error={error?.fields.fullName}
          autoFocus
        />
        <Field
          label={t('auth.username')}
          name="username"
          autoComplete="username"
          hint={t('auth.username.hint')}
          value={form.username}
          onChange={set('username')}
          error={error?.fields.username}
        />
        <Field
          label={t('auth.email')}
          name="email"
          type="email"
          autoComplete="email"
          icon={<Mail size={18} aria-hidden="true" />}
          value={form.email}
          onChange={set('email')}
          error={error?.fields.email}
        />
        <div>
          <Field
            label={t('auth.password')}
            name="password"
            type="password"
            autoComplete="new-password"
            icon={<Lock size={18} aria-hidden="true" />}
            value={form.password}
            onChange={set('password')}
            error={error?.fields.password}
          />
          <ul className="checklist" aria-label={t('auth.rules')}>
            {rules.map(({ key, ok }) => (
              <li key={key} className={ok ? 'ok' : undefined}>
                {ok ? <Check size={14} aria-hidden="true" /> : <Circle size={14} aria-hidden="true" />}
                {t(key)}
                <span className="sr-only">{ok ? ' ✓' : ''}</span>
              </li>
            ))}
          </ul>
        </div>
        <Button type="submit" size="lg" block loading={busy}>
          {t('auth.register.submit')}
        </Button>
      </form>
      <p className="muted" style={{ marginTop: 24, textAlign: 'center' }}>
        {t('auth.register.hasAccount')} <Link to="/login">{t('nav.login')}</Link>
      </p>
    </AuthLayout>
  );
}
