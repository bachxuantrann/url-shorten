import { useState, type FormEvent } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { AlertCircle, CheckCircle2, Flag, Link2 } from 'lucide-react';
import { reportAbuse, ABUSE_REASONS } from '../api/reports';
import type { AbuseReason } from '../api/types';
import { Field } from '../components/Field';
import { Button, buttonClass } from '../components/ui/Button';
import { useDocumentTitle } from '../hooks/useDocumentTitle';
import { useSubmit } from '../hooks/useSubmit';
import { useI18n } from '../i18n/I18nProvider';
import type { MessageKey } from '../i18n/messages';
import { extractShortCode } from '../utils/shortCode';

/**
 * Trang công khai báo cáo lạm dụng một link (không cần đăng nhập). Có thể mở sẵn với `?code=abc1234` từ trang
 * nhập mật khẩu của link; người dùng cũng dán được cả link rút gọn đầy đủ.
 */
export function ReportPage() {
  const { t } = useI18n();
  useDocumentTitle(t('report.title'));
  const [params] = useSearchParams();
  const { busy, error, run } = useSubmit();

  const [link, setLink] = useState(params.get('code') ?? '');
  const [reason, setReason] = useState<AbuseReason | ''>('');
  const [details, setDetails] = useState('');
  const [codeError, setCodeError] = useState<string | null>(null);
  const [done, setDone] = useState(false);

  async function submit(e: FormEvent) {
    e.preventDefault();
    const shortCode = extractShortCode(link);
    if (!shortCode) {
      setCodeError(t('report.err.code'));
      return;
    }
    if (!reason) return;
    setCodeError(null);
    const ok = await run(() => reportAbuse({ shortCode, reason, details: details.trim() || undefined }));
    if (ok) setDone(true);
  }

  function reset() {
    setLink('');
    setReason('');
    setDetails('');
    setDone(false);
  }

  if (done) {
    return (
      <div className="status-page">
        <div className="card status-card">
          <div className="status-icon">
            <CheckCircle2 size={28} aria-hidden="true" />
          </div>
          <h1>{t('report.done.title')}</h1>
          <p className="desc">{t('report.done.text')}</p>
          <div className="status-actions">
            <Link to="/" className={buttonClass({ size: 'lg', block: true })}>
              {t('unavailable.home')}
            </Link>
            <Button variant="ghost" block onClick={reset}>
              {t('report.done.again')}
            </Button>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="status-page">
      <div className="card status-card report-card">
        <div className="status-icon danger">
          <Flag size={28} aria-hidden="true" />
        </div>
        <h1>{t('report.title')}</h1>
        <p className="desc">{t('report.sub')}</p>
        <form className="form" onSubmit={submit} noValidate>
          {error?.message && (
            <div className="alert" role="alert">
              <AlertCircle size={18} aria-hidden="true" />
              <span>{error.message}</span>
            </div>
          )}
          <Field
            label={t('report.field.link')}
            name="link"
            icon={<Link2 size={18} aria-hidden="true" />}
            placeholder={t('report.field.link.placeholder')}
            autoComplete="off"
            value={link}
            maxLength={2048}
            onChange={(e) => setLink(e.target.value)}
            error={codeError ?? error?.fields.shortCode}
            autoFocus={!params.get('code')}
          />
          <fieldset className="choices">
            <legend>{t('report.reason')}</legend>
            {ABUSE_REASONS.map((r) => (
              <label key={r} className="choice">
                <input type="radio" name="reason" value={r} checked={reason === r} onChange={() => setReason(r)} />
                <span>{t(`report.reason.${r}` as MessageKey)}</span>
              </label>
            ))}
            {error?.fields.reason && (
              <span className="field-error" role="alert">
                {error.fields.reason}
              </span>
            )}
          </fieldset>
          <div className="field">
            <label htmlFor="report-details">{t('report.details')}</label>
            <textarea
              id="report-details"
              className="textarea"
              rows={4}
              maxLength={500}
              value={details}
              onChange={(e) => setDetails(e.target.value)}
              aria-describedby="report-details-hint"
            />
            <span id="report-details-hint" className="field-hint">
              {error?.fields.details ?? t('report.details.hint')}
            </span>
          </div>
          <Button type="submit" size="lg" block loading={busy} disabled={link.trim() === '' || reason === ''}>
            {t('report.submit')}
          </Button>
        </form>
      </div>
    </div>
  );
}
