import { useEffect, useState, type FormEvent } from 'react';
import { Link, useLocation } from 'react-router-dom';
import {
  AlertCircle, ArrowRight, BarChart3, CalendarClock, ChevronDown, ExternalLink, Link2, Lock, QrCode, ShieldCheck, Sparkles, Tag,
} from 'lucide-react';
import { request } from '../api/client';
import { toErrorView, type ErrorView } from '../api/errors';
import type { UrlResponse } from '../api/types';
import { useAuth } from '../auth/AuthProvider';
import { Field } from '../components/Field';
import { Button, buttonClass } from '../components/ui/Button';
import { CopyButton } from '../components/ui/CopyButton';
import { useDocumentTitle } from '../hooks/useDocumentTitle';
import { useI18n } from '../i18n/I18nProvider';
import type { MessageKey } from '../i18n/messages';
import { useServerTime } from '../hooks/useServerTime';
import { displayUrl } from '../utils/datetime';

const FEATURES = [
  { icon: BarChart3, key: 'analytics' },
  { icon: QrCode, key: 'qr' },
  { icon: Lock, key: 'password' },
  { icon: Tag, key: 'alias' },
  { icon: CalendarClock, key: 'expiry' },
  { icon: ShieldCheck, key: 'safe' },
] as const;

/** Trang chủ: rút gọn ẩn danh ngay ở màn hình đầu, rồi giới thiệu tính năng, cách dùng và hỏi đáp. */
export function HomePage() {
  const { t } = useI18n();
  const { user } = useAuth();
  useDocumentTitle(null);
  const time = useServerTime();
  const { hash } = useLocation();

  // Mở thẳng /#faq: trình duyệt xử lý hash trước khi React dựng xong nội dung nên không tự cuộn tới; cuộn thủ công sau khi dựng
  useEffect(() => {
    if (hash) document.getElementById(decodeURIComponent(hash.slice(1)))?.scrollIntoView();
  }, [hash]);

  const [originalUrl, setOriginalUrl] = useState('');
  const [expiresAt, setExpiresAt] = useState('');
  const [options, setOptions] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ErrorView | null>(null);
  const [result, setResult] = useState<UrlResponse | null>(null);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const created = await request<UrlResponse>('/api/v1/public/urls', {
        method: 'POST',
        body: { originalUrl: originalUrl.trim(), expiresAt: time.fromInput(expiresAt) },
      });
      setResult(created);
    } catch (err) {
      setError(toErrorView(err, t));
    } finally {
      setBusy(false);
    }
  }

  function reset() {
    setResult(null);
    setOriginalUrl('');
    setExpiresAt('');
    setOptions(false);
  }

  return (
    <>
      <section className="hero">
        <div className="page">
          <span className="pill">
            <Sparkles size={14} aria-hidden="true" />
            {t('home.badge')}
          </span>
          <h1>
            {t('home.title.before')} <em>{t('home.title.highlight')}</em>
          </h1>
          <p className="lead">{t('home.lead')}</p>

          <div className="card shorten-box">
            {result ? (
              <div aria-live="polite">
                <h2 className="sr-only">{t('home.result.title')}</h2>
                <div className="result-link">
                  <a href={result.shortUrl} target="_blank" rel="noopener noreferrer">
                    {displayUrl(result.shortUrl)}
                  </a>
                  <span className="spacer" />
                  <CopyButton text={result.shortUrl} label size="md" variant="primary" />
                </div>
                <p className="result-orig truncate">{t('home.result.original', { url: result.originalUrl })}</p>
                <div className="row" style={{ marginTop: 14 }}>
                  <a className={buttonClass({ variant: 'secondary' })} href={result.shortUrl} target="_blank" rel="noopener noreferrer">
                    <ExternalLink size={16} aria-hidden="true" />
                    {t('common.open')}
                  </a>
                  <Button variant="ghost" onClick={reset}>
                    {t('home.another')}
                  </Button>
                </div>
                <div className="upsell">
                  <div className="upsell-text">
                    <strong>{t('home.upsell.title')}</strong>
                    <br />
                    {user ? t('home.upsell.loggedIn') : t('home.upsell.text')}
                  </div>
                  {!user && (
                    <Link to="/register" className={buttonClass({})}>
                      {t('home.upsell.cta')}
                    </Link>
                  )}
                </div>
              </div>
            ) : (
              <form onSubmit={submit} noValidate>
                {error && (
                  <div className="alert" role="alert" style={{ marginBottom: 12 }}>
                    <AlertCircle size={18} aria-hidden="true" />
                    <span>{error.message}</span>
                  </div>
                )}
                <div className="shorten-bar">
                  <div style={{ flex: 1 }}>
                    <Field
                      label={t('home.input.label')}
                      hideLabel
                      name="originalUrl"
                      type="url"
                      icon={<Link2 size={18} aria-hidden="true" />}
                      placeholder={t('home.input.placeholder')}
                      value={originalUrl}
                      onChange={(e) => setOriginalUrl(e.target.value)}
                      error={error?.fields.originalUrl}
                      required
                    />
                  </div>
                  <Button type="submit" size="lg" loading={busy} disabled={originalUrl.trim() === ''}>
                    {t('home.submit')}
                    {!busy && <ArrowRight size={18} aria-hidden="true" />}
                  </Button>
                </div>
                <div className="shorten-opts">
                  <button type="button" className="link-toggle" aria-expanded={options} onClick={() => setOptions((o) => !o)}>
                    <ChevronDown size={16} aria-hidden="true" style={{ transform: options ? 'rotate(180deg)' : undefined }} />
                    {t('home.options')}
                  </button>
                  {options && (
                    <div style={{ maxWidth: 320, marginTop: 10 }}>
                      <Field
                        label={t('home.expires')}
                        name="expiresAt"
                        type="datetime-local"
                        hint={`${t('home.expires.hint')} ${t('time.yourZone', { zone: time.userZoneLabel })}`}
                        value={expiresAt}
                        onChange={(e) => setExpiresAt(e.target.value)}
                        error={error?.fields.expiresAt}
                      />
                    </div>
                  )}
                </div>
              </form>
            )}
          </div>
        </div>
      </section>

      <div className="page">
        <section id="features" className="section" aria-labelledby="features-title">
          <div className="section-head">
            <h2 id="features-title">{t('features.title')}</h2>
            <p>{t('features.sub')}</p>
          </div>
          <div className="features">
            {FEATURES.map(({ icon: Icon, key }) => (
              <article key={key} className="card feature">
                <span className="icon-box">
                  <Icon size={20} aria-hidden="true" />
                </span>
                <h3>{t(`feature.${key}.t` as MessageKey)}</h3>
                <p>{t(`feature.${key}.d` as MessageKey)}</p>
              </article>
            ))}
          </div>
        </section>

        <section id="how" className="section" aria-labelledby="how-title">
          <div className="section-head">
            <h2 id="how-title">{t('how.title')}</h2>
            <p>{t('how.sub')}</p>
          </div>
          <ol className="steps" style={{ listStyle: 'none', padding: 0, margin: 0 }}>
            {[1, 2, 3].map((n) => (
              <li key={n} className="card step">
                <div className="step-num" aria-hidden="true">{n}</div>
                <h3>{t(`how.${n}.t` as MessageKey)}</h3>
                <p>{t(`how.${n}.d` as MessageKey)}</p>
              </li>
            ))}
          </ol>
        </section>

        <section id="faq" className="section" aria-labelledby="faq-title">
          <div className="section-head">
            <h2 id="faq-title">{t('faq.title')}</h2>
          </div>
          <div className="faq">
            {[1, 2, 3, 4, 5, 6].map((n) => (
              <details key={n}>
                <summary>
                  {t(`faq.${n}.q` as MessageKey)}
                  <ChevronDown size={18} aria-hidden="true" />
                </summary>
                <p>{t(`faq.${n}.a` as MessageKey)}</p>
              </details>
            ))}
          </div>
        </section>

        {!user && (
          <section className="cta-band" aria-labelledby="cta-title">
            <h2 id="cta-title">{t('cta.title')}</h2>
            <p>{t('cta.text')}</p>
            <Link to="/register" className={buttonClass({ size: 'lg' })}>
              {t('cta.button')}
              <ArrowRight size={18} aria-hidden="true" />
            </Link>
          </section>
        )}
      </div>
    </>
  );
}
