import { useState, type FormEvent } from 'react';
import { AlertCircle, Monitor, Moon, Sun } from 'lucide-react';
import { changePassword, updateProfile } from '../api/users';
import { useAuth } from '../auth/AuthProvider';
import { Field } from '../components/Field';
import { Button } from '../components/ui/Button';
import { useToast } from '../components/ui/Toast';
import { useDocumentTitle } from '../hooks/useDocumentTitle';
import { useSubmit } from '../hooks/useSubmit';
import { useI18n } from '../i18n/I18nProvider';
import type { Language } from '../i18n/messages';
import { useTheme, type ThemeMode } from '../theme/ThemeProvider';

const THEMES: { mode: ThemeMode; icon: typeof Sun }[] = [
  { mode: 'light', icon: Sun },
  { mode: 'dark', icon: Moon },
  { mode: 'system', icon: Monitor },
];

/** Cài đặt tài khoản: hồ sơ, đổi mật khẩu, giao diện và ngôn ngữ. */
export function SettingsPage() {
  const { t } = useI18n();
  useDocumentTitle(t('settings.title'));
  return (
    <div className="stack" style={{ maxWidth: 720 }}>
      <div className="page-head">
        <div>
          <h1>{t('settings.title')}</h1>
          <p>{t('settings.sub')}</p>
        </div>
      </div>
      <ProfileCard />
      <PasswordCard />
      <AppearanceCard />
    </div>
  );
}

function Banner({ message }: { message?: string }) {
  if (!message) return null;
  return (
    <div className="alert" role="alert">
      <AlertCircle size={18} aria-hidden="true" />
      <span>{message}</span>
    </div>
  );
}

function ProfileCard() {
  const { t } = useI18n();
  const { user, setUser } = useAuth();
  const toast = useToast();
  const { busy, error, run } = useSubmit();
  const [fullName, setFullName] = useState(user?.fullName ?? '');
  const [email, setEmail] = useState(user?.email ?? '');

  // Chỉ cho lưu khi có thay đổi thật so với hồ sơ hiện tại (khoảng trắng đầu/cuối không tính); sau khi lưu thành công user được
  // cập nhật nên nút tự khoá lại
  const dirty = fullName.trim() !== (user?.fullName ?? '') || email.trim() !== (user?.email ?? '');

  async function submit(e: FormEvent) {
    e.preventDefault();
    const ok = await run(async () => setUser(await updateProfile({ fullName: fullName.trim(), email: email.trim() })));
    if (ok) toast.success(t('toast.profileSaved'));
  }

  return (
    <section className="card">
      <div className="card-header">
        <div>
          <h2>{t('settings.profile')}</h2>
          <p className="muted small">{t('settings.profile.sub')}</p>
        </div>
      </div>
      <form className="form card-pad" onSubmit={submit} noValidate>
        <Banner message={error?.message} />
        <Field label={t('settings.username')} value={user?.username ?? ''} readOnly hint={t('settings.username.hint')} />
        <div className="form-row">
          <Field label={t('auth.fullName')} name="fullName" autoComplete="name" value={fullName} onChange={(e) => setFullName(e.target.value)} error={error?.fields.fullName} />
          <Field label={t('auth.email')} name="email" type="email" autoComplete="email" value={email} onChange={(e) => setEmail(e.target.value)} error={error?.fields.email} />
        </div>
        <div>
          <Button type="submit" loading={busy} disabled={!dirty || fullName.trim() === '' || email.trim() === ''}>
            {t('common.save')}
          </Button>
        </div>
      </form>
    </section>
  );
}

function PasswordCard() {
  const { t } = useI18n();
  const toast = useToast();
  const { busy, error, run } = useSubmit();
  const [oldPassword, setOld] = useState('');
  const [newPassword, setNew] = useState('');
  const [confirmPassword, setConfirm] = useState('');

  async function submit(e: FormEvent) {
    e.preventDefault();
    const ok = await run(() => changePassword({ oldPassword, newPassword, confirmPassword }));
    if (ok) {
      toast.success(t('toast.passwordChanged'));
      setOld('');
      setNew('');
      setConfirm('');
    }
  }

  return (
    <section className="card">
      <div className="card-header">
        <div>
          <h2>{t('settings.security')}</h2>
          <p className="muted small">{t('settings.security.sub')}</p>
        </div>
      </div>
      <form className="form card-pad" onSubmit={submit} noValidate>
        <Banner message={error?.message} />
        <Field label={t('settings.oldPassword')} name="oldPassword" type="password" autoComplete="current-password" value={oldPassword} onChange={(e) => setOld(e.target.value)} error={error?.fields.oldPassword} />
        <div className="form-row">
          <Field label={t('settings.newPassword')} name="newPassword" type="password" autoComplete="new-password" hint={t('auth.rule.len')} value={newPassword} onChange={(e) => setNew(e.target.value)} error={error?.fields.newPassword} />
          <Field label={t('settings.confirmPassword')} name="confirmPassword" type="password" autoComplete="new-password" value={confirmPassword} onChange={(e) => setConfirm(e.target.value)} error={error?.fields.confirmPassword} />
        </div>
        <div>
          <Button type="submit" loading={busy} disabled={!oldPassword || !newPassword || !confirmPassword}>
            {t('settings.changePassword')}
          </Button>
        </div>
      </form>
    </section>
  );
}

function AppearanceCard() {
  const { t, language, setLanguage } = useI18n();
  const { mode, setMode } = useTheme();

  return (
    <section className="card">
      <div className="card-header">
        <div>
          <h2>{t('settings.appearance')}</h2>
          <p className="muted small">{t('settings.appearance.sub')}</p>
        </div>
      </div>
      <div className="form card-pad">
        <div className="field">
          <span className="label">{t('theme.toggle')}</span>
          <div className="segmented" role="group" aria-label={t('theme.toggle')} style={{ alignSelf: 'flex-start' }}>
            {THEMES.map(({ mode: m, icon: Icon }) => (
              <button key={m} type="button" aria-pressed={mode === m} onClick={() => setMode(m)}>
                <span className="row" style={{ gap: 6, flexWrap: 'nowrap' }}>
                  <Icon size={14} aria-hidden="true" />
                  {t(`theme.${m}`)}
                </span>
              </button>
            ))}
          </div>
        </div>
        <div className="field">
          <span className="label">{t('lang.label')}</span>
          <div className="segmented" role="group" aria-label={t('lang.label')} style={{ alignSelf: 'flex-start' }}>
            {(['vi', 'en'] as Language[]).map((l) => (
              <button key={l} type="button" aria-pressed={language === l} onClick={() => setLanguage(l)}>
                {t(`lang.${l}`)}
              </button>
            ))}
          </div>
        </div>
      </div>
    </section>
  );
}
