import { useState, type FormEvent } from 'react';
import { Link, useParams } from 'react-router-dom';
import { AlertCircle, ArrowRight, Clock, Lock, SearchX, ShieldAlert, type LucideIcon } from 'lucide-react';
import { toErrorView, type ErrorView } from '../api/errors';
import { unlockLink } from '../api/stats';
import { Field } from '../components/Field';
import { Button, buttonClass } from '../components/ui/Button';
import { useDocumentTitle } from '../hooks/useDocumentTitle';
import { useI18n } from '../i18n/I18nProvider';
import type { MessageKey } from '../i18n/messages';

/** Đích chỉ được là http/https (server đã kiểm tra, đây là lớp phòng thủ thứ hai chống javascript:/data:). */
function isSafeTarget(url: string): boolean {
  try {
    const p = new URL(url).protocol;
    return p === 'http:' || p === 'https:';
  } catch {
    return false;
  }
}

// ===========================
//  Nhập mật khẩu của link được bảo vệ
// ===========================

/** Người lạ nhập mật khẩu của link được bảo vệ (GET /{code} của trình duyệt được chuyển về đây). */
export function UnlockPage() {
  const { code = '' } = useParams();
  const { t } = useI18n();
  useDocumentTitle(t('unlock.title'));
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ErrorView | null>(null);
  const [redirecting, setRedirecting] = useState(false);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const { originalUrl } = await unlockLink(code, password);
      if (!isSafeTarget(originalUrl)) {
        setError({ message: t('unlock.unsafe'), fields: {} });
        return;
      }
      setRedirecting(true);
      window.location.assign(originalUrl);
    } catch (err) {
      setError(toErrorView(err, t));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="status-page">
      <div className="card status-card">
        <div className="status-icon">
          <Lock size={28} aria-hidden="true" />
        </div>
        <h1>{t('unlock.title')}</h1>
        <p className="desc">{t('unlock.sub')}</p>
        <form className="form" onSubmit={submit} noValidate>
          {error && (
            <div className="alert" role="alert">
              <AlertCircle size={18} aria-hidden="true" />
              <span>{error.message}</span>
            </div>
          )}
          <Field
            label={t('unlock.password')}
            name="password"
            type="password"
            autoComplete="off"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            error={error?.fields.password}
            autoFocus
          />
          <Button type="submit" size="lg" block loading={busy || redirecting} disabled={password === ''}>
            {redirecting ? t('unlock.redirecting') : t('unlock.submit')}
          </Button>
        </form>
        <p className="muted small" style={{ marginTop: 20 }}>
          {t('unlock.own')} <Link to="/">{t('unavailable.create')}</Link>
        </p>
        <p className="muted small" style={{ marginTop: 8 }}>
          <Link to={`/report?code=${encodeURIComponent(code)}`}>{t('report.link')}</Link>
        </p>
      </div>
    </div>
  );
}

// ===========================
//  Link không dùng được (404 / 410 / 403) và trang 404 của UI
// ===========================

type Reason = 'not-found' | 'gone' | 'blocked';

const REASONS: Record<Reason, { icon: LucideIcon; tone: '' | 'warning' | 'danger' }> = {
  'not-found': { icon: SearchX, tone: '' },
  gone: { icon: Clock, tone: 'warning' },
  blocked: { icon: ShieldAlert, tone: 'danger' },
};

/** Trang người bấm link chết được đưa tới: nói rõ vì sao (không có / hết hạn hoặc xoá / bị chặn) và cho lối đi tiếp. */
export function UnavailablePage() {
  const { reason = 'not-found' } = useParams();
  const { t } = useI18n();
  const known: Reason = reason in REASONS ? (reason as Reason) : 'not-found';
  const { icon: Icon, tone } = REASONS[known];
  useDocumentTitle(t(`unavailable.${known}.title` as MessageKey));

  return (
    <div className="status-page">
      <div className="card status-card">
        <div className={`status-icon ${tone}`}>
          <Icon size={28} aria-hidden="true" />
        </div>
        <div className="status-code">{t(`unavailable.code.${known}` as MessageKey)}</div>
        <h1>{t(`unavailable.${known}.title` as MessageKey)}</h1>
        <p className="desc">{t(`unavailable.${known}.text` as MessageKey)}</p>
        <div className="status-actions">
          <Link to="/" className={buttonClass({ size: 'lg', block: true })}>
            {t('unavailable.home')}
          </Link>
          <Link to="/" className={buttonClass({ variant: 'ghost', block: true })}>
            {t('unavailable.create')}
            <ArrowRight size={16} aria-hidden="true" />
          </Link>
        </div>
      </div>
    </div>
  );
}

/** Đường dẫn của UI không tồn tại (khác với link rút gọn không tồn tại). */
export function NotFoundPage() {
  const { t } = useI18n();
  useDocumentTitle(t('ui404.title'));
  return (
    <div className="status-page">
      <div className="card status-card">
        <div className="status-icon">
          <SearchX size={28} aria-hidden="true" />
        </div>
        <div className="status-code">404</div>
        <h1>{t('ui404.title')}</h1>
        <p className="desc">{t('ui404.text')}</p>
        <div className="status-actions">
          <Link to="/" className={buttonClass({ size: 'lg', block: true })}>
            {t('unavailable.home')}
          </Link>
        </div>
      </div>
    </div>
  );
}
