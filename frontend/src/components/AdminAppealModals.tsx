import { useState, type FormEvent } from 'react';
import { acceptAppeal, rejectAppeal } from '../api/admin';
import type { AdminAppeal } from '../api/types';
import { useSubmit } from '../hooks/useSubmit';
import { useI18n } from '../i18n/I18nProvider';
import { Button } from './ui/Button';
import { Modal } from './ui/Modal';
import { useToast } from './ui/Toast';

interface ModalProps {
  appeal: AdminAppeal | null;
  onClose: () => void;
  /** Gọi sau khi server đã xử lý kháng nghị. */
  onDone: () => void;
}

function ErrorBanner({ message }: { message?: string }) {
  if (!message) return null;
  return (
    <div className="alert" role="alert">
      <span>{message}</span>
    </div>
  );
}

// ===========================
//  Chấp nhận (gỡ chặn link)
// ===========================

export function AcceptAppealModal({ appeal, onClose, onDone }: ModalProps) {
  const { t } = useI18n();
  return (
    <Modal open={appeal !== null} onClose={onClose} title={t('admin.appeals.accept.title')}>
      {appeal && <AcceptBody appeal={appeal} onClose={onClose} onDone={onDone} />}
    </Modal>
  );
}

function AcceptBody({ appeal, onClose, onDone }: ModalProps & { appeal: AdminAppeal }) {
  const { t } = useI18n();
  const toast = useToast();
  const { busy, error, run } = useSubmit();

  async function confirm() {
    const ok = await run(() => acceptAppeal(appeal.id));
    if (ok) {
      toast.success(t('toast.appealAccepted'));
      onDone();
      onClose();
    }
  }

  return (
    <div className="form">
      <ErrorBanner message={error?.message} />
      <p className="muted">{t('admin.appeals.accept.text', { url: appeal.shortUrl })}</p>
      <div className="modal-foot" style={{ padding: 0 }}>
        <Button variant="secondary" onClick={onClose}>
          {t('common.cancel')}
        </Button>
        <Button loading={busy} onClick={confirm}>
          {t('admin.appeals.accept')}
        </Button>
      </div>
    </div>
  );
}

// ===========================
//  Từ chối (kèm phản hồi tuỳ chọn)
// ===========================

export function RejectAppealModal({ appeal, onClose, onDone }: ModalProps) {
  const { t } = useI18n();
  return (
    <Modal open={appeal !== null} onClose={onClose} title={t('admin.appeals.reject.title')}>
      {appeal && <RejectForm appeal={appeal} onClose={onClose} onDone={onDone} />}
    </Modal>
  );
}

function RejectForm({ appeal, onClose, onDone }: ModalProps & { appeal: AdminAppeal }) {
  const { t } = useI18n();
  const toast = useToast();
  const { busy, error, run } = useSubmit();
  const [response, setResponse] = useState('');

  async function submit(e: FormEvent) {
    e.preventDefault();
    const ok = await run(() => rejectAppeal(appeal.id, response.trim() || undefined));
    if (ok) {
      toast.success(t('toast.appealRejected'));
      onDone();
      onClose();
    }
  }

  return (
    <form className="form" onSubmit={submit} noValidate>
      <ErrorBanner message={error?.message} />
      <p className="muted">{t('admin.appeals.reject.text', { url: appeal.shortUrl })}</p>
      <div className="field">
        <label htmlFor="appeal-response">{t('admin.appeals.reject.response')}</label>
        <textarea
          id="appeal-response"
          className="textarea"
          rows={3}
          maxLength={255}
          value={response}
          onChange={(e) => setResponse(e.target.value)}
          aria-describedby="appeal-response-hint"
          autoFocus
        />
        <span id="appeal-response-hint" className={error?.fields.response ? 'field-error' : 'field-hint'}>
          {error?.fields.response ?? t('admin.appeals.reject.hint')}
        </span>
      </div>
      <div className="modal-foot" style={{ padding: 0 }}>
        <Button variant="secondary" onClick={onClose}>
          {t('common.cancel')}
        </Button>
        <Button type="submit" variant="danger" loading={busy}>
          {t('admin.appeals.reject.confirm')}
        </Button>
      </div>
    </form>
  );
}
