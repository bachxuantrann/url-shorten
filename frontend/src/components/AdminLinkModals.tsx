import { useState, type FormEvent } from 'react';
import { blockUrl, unblockUrl } from '../api/admin';
import type { AdminUrl } from '../api/types';
import { useSubmit } from '../hooks/useSubmit';
import { useI18n } from '../i18n/I18nProvider';
import { Field } from './Field';
import { Button } from './ui/Button';
import { Modal } from './ui/Modal';
import { useToast } from './ui/Toast';

/** Phần của link mà hộp thoại cần: đủ để dùng cả từ danh sách link lẫn danh sách báo cáo. */
export type LinkRef = Pick<AdminUrl, 'shortCode' | 'shortUrl'>;

interface ModalProps {
  link: LinkRef | null;
  onClose: () => void;
  /** Gọi sau khi server đã chặn / gỡ chặn, kèm bản mới của link. */
  onChanged: (updated: AdminUrl) => void;
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
//  Chặn
// ===========================

export function BlockModal({ link, onClose, onChanged }: ModalProps) {
  const { t } = useI18n();
  return (
    <Modal open={link !== null} onClose={onClose} title={t('admin.block.title')}>
      {link && <BlockForm link={link} onClose={onClose} onChanged={onChanged} />}
    </Modal>
  );
}

function BlockForm({ link, onClose, onChanged }: ModalProps & { link: LinkRef }) {
  const { t } = useI18n();
  const toast = useToast();
  const { busy, error, run } = useSubmit();
  const [reason, setReason] = useState('');

  async function submit(e: FormEvent) {
    e.preventDefault();
    let updated: AdminUrl | undefined;
    const ok = await run(async () => {
      updated = await blockUrl(link.shortCode, reason.trim());
    });
    if (ok && updated) {
      toast.success(t('toast.blocked'));
      onChanged(updated);
      onClose();
    }
  }

  return (
    <form className="form" onSubmit={submit} noValidate>
      <ErrorBanner message={error?.message} />
      <p className="muted">{t('admin.block.text', { url: link.shortUrl })}</p>
      <Field
        label={t('admin.block.reason')}
        name="reason"
        hint={t('admin.block.reason.hint')}
        value={reason}
        maxLength={255}
        onChange={(e) => setReason(e.target.value)}
        error={error?.fields.reason}
        autoFocus
        required
      />
      <div className="modal-foot" style={{ padding: 0 }}>
        <Button variant="secondary" onClick={onClose}>
          {t('common.cancel')}
        </Button>
        <Button type="submit" variant="danger" loading={busy} disabled={reason.trim() === ''}>
          {t('admin.block.confirm')}
        </Button>
      </div>
    </form>
  );
}

// ===========================
//  Gỡ chặn
// ===========================

export function UnblockModal({ link, onClose, onChanged }: ModalProps) {
  const { t } = useI18n();
  return (
    <Modal open={link !== null} onClose={onClose} title={t('admin.unblock.title')}>
      {link && <UnblockBody link={link} onClose={onClose} onChanged={onChanged} />}
    </Modal>
  );
}

function UnblockBody({ link, onClose, onChanged }: ModalProps & { link: LinkRef }) {
  const { t } = useI18n();
  const toast = useToast();
  const { busy, error, run } = useSubmit();

  async function confirm() {
    let updated: AdminUrl | undefined;
    const ok = await run(async () => {
      updated = await unblockUrl(link.shortCode);
    });
    if (ok && updated) {
      toast.success(t('toast.unblocked'));
      onChanged(updated);
      onClose();
    }
  }

  return (
    <div className="form">
      <ErrorBanner message={error?.message} />
      <p className="muted">{t('admin.unblock.text', { url: link.shortUrl })}</p>
      <div className="modal-foot" style={{ padding: 0 }}>
        <Button variant="secondary" onClick={onClose}>
          {t('common.cancel')}
        </Button>
        <Button loading={busy} onClick={confirm}>
          {t('admin.unblock.confirm')}
        </Button>
      </div>
    </div>
  );
}
