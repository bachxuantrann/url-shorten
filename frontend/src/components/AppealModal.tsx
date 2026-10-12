import { useState, type FormEvent } from 'react';
import type { UrlResponse } from '../api/types';
import { appealLink } from '../api/urls';
import { useSubmit } from '../hooks/useSubmit';
import { useI18n } from '../i18n/I18nProvider';
import { Button } from './ui/Button';
import { Modal } from './ui/Modal';
import { useToast } from './ui/Toast';

interface Props {
  link: UrlResponse | null;
  onClose: () => void;
  /** Nhận bản mới của link (đã có kháng nghị đang chờ). */
  onChanged: (updated: UrlResponse) => void;
}

/** Chủ link bị chặn viết và gửi kháng nghị (tối đa 500 ký tự) để quản trị viên xem xét lại. */
export function AppealModal({ link, onClose, onChanged }: Props) {
  const { t } = useI18n();
  return (
    <Modal open={link !== null} onClose={onClose} title={t('modal.appeal.title')}>
      {link && <AppealForm link={link} onClose={onClose} onChanged={onChanged} />}
    </Modal>
  );
}

function AppealForm({ link, onClose, onChanged }: Props & { link: UrlResponse }) {
  const { t } = useI18n();
  const toast = useToast();
  const { busy, error, run } = useSubmit();
  const [message, setMessage] = useState('');

  async function submit(e: FormEvent) {
    e.preventDefault();
    let updated: UrlResponse | undefined;
    const ok = await run(async () => {
      updated = await appealLink(link.shortCode, message.trim());
    });
    if (ok && updated) {
      toast.success(t('toast.appealSent'));
      onChanged(updated);
      onClose();
    }
  }

  return (
    <form className="form" onSubmit={submit} noValidate>
      {error?.message && (
        <div className="alert" role="alert">
          <span>{error.message}</span>
        </div>
      )}
      <p className="muted">{t('modal.appeal.text', { url: link.shortUrl })}</p>
      {link.blockedReason && <p className="muted small">{t('modal.appeal.reason', { reason: link.blockedReason })}</p>}
      <div className="field">
        <label htmlFor="appeal-message">{t('modal.appeal.message')}</label>
        <textarea
          id="appeal-message"
          className="textarea"
          rows={5}
          maxLength={500}
          value={message}
          onChange={(e) => setMessage(e.target.value)}
          aria-describedby="appeal-message-hint"
          aria-invalid={error?.fields.message ? true : undefined}
          autoFocus
        />
        <span id="appeal-message-hint" className={error?.fields.message ? 'field-error' : 'field-hint'}>
          {error?.fields.message ?? t('modal.appeal.hint')}
        </span>
      </div>
      <div className="modal-foot" style={{ padding: 0 }}>
        <Button variant="secondary" onClick={onClose}>
          {t('common.cancel')}
        </Button>
        <Button type="submit" loading={busy} disabled={message.trim() === ''}>
          {t('modal.appeal.submit')}
        </Button>
      </div>
    </form>
  );
}
