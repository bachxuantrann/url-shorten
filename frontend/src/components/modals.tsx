import { useEffect, useState, type FormEvent } from 'react';
import { AlertCircle, ChevronDown, Download, Link2 } from 'lucide-react';
import type { UrlResponse } from '../api/types';
import { createUrl, deleteUrl, removeLinkPassword, setLinkPassword, updateUrl } from '../api/urls';
import { getQrPng } from '../api/stats';
import { useSubmit } from '../hooks/useSubmit';
import { useI18n } from '../i18n/I18nProvider';
import { fromInputValue, toInputValue } from '../utils/datetime';
import { Field } from './Field';
import { Button, buttonClass } from './ui/Button';
import { Modal } from './ui/Modal';
import { useToast } from './ui/Toast';

function ErrorBanner({ message }: { message?: string }) {
  if (!message) return null;
  return (
    <div className="alert" role="alert">
      <AlertCircle size={18} aria-hidden="true" />
      <span>{message}</span>
    </div>
  );
}

// ===========================
//  Tạo link
// ===========================

interface CreateProps {
  open: boolean;
  onClose: () => void;
  onCreated: (link: UrlResponse) => void;
}

export function CreateLinkModal({ open, onClose, onCreated }: CreateProps) {
  const { t } = useI18n();
  return (
    <Modal open={open} onClose={onClose} title={t('modal.create.title')} description={t('modal.create.sub')}>
      <CreateForm onClose={onClose} onCreated={onCreated} />
    </Modal>
  );
}

function CreateForm({ onClose, onCreated }: Omit<CreateProps, 'open'>) {
  const { t } = useI18n();
  const toast = useToast();
  const { busy, error, run } = useSubmit();
  const [url, setUrl] = useState('');
  const [alias, setAlias] = useState('');
  const [password, setPassword] = useState('');
  const [expiresAt, setExpiresAt] = useState('');
  const [advanced, setAdvanced] = useState(false);

  async function submit(e: FormEvent) {
    e.preventDefault();
    let created: UrlResponse | undefined;
    const ok = await run(async () => {
      created = await createUrl({
        originalUrl: url.trim(),
        alias: alias.trim() || undefined,
        password: password || undefined,
        expiresAt: fromInputValue(expiresAt),
      });
    });
    if (ok && created) {
      toast.success(t('toast.created'));
      onCreated(created);
    }
  }

  return (
    <form className="form" onSubmit={submit} noValidate>
      <ErrorBanner message={error?.message} />
      <Field
        label={t('form.url')}
        name="originalUrl"
        type="url"
        icon={<Link2 size={18} aria-hidden="true" />}
        placeholder={t('form.url.placeholder')}
        value={url}
        onChange={(e) => setUrl(e.target.value)}
        error={error?.fields.originalUrl}
        autoFocus
        required
      />
      <button type="button" className="link-toggle" aria-expanded={advanced} onClick={() => setAdvanced((a) => !a)}>
        <ChevronDown size={16} aria-hidden="true" style={{ transform: advanced ? 'rotate(180deg)' : undefined }} />
        {t('form.advanced')}
      </button>
      {advanced && (
        <>
          <Field
            label={t('form.alias')}
            name="alias"
            autoComplete="off"
            placeholder={t('form.alias.placeholder')}
            hint={t('form.alias.hint')}
            value={alias}
            onChange={(e) => setAlias(e.target.value)}
            error={error?.fields.alias}
          />
          <Field
            label={t('form.password')}
            name="password"
            type="password"
            autoComplete="new-password"
            hint={t('form.password.hint')}
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            error={error?.fields.password}
          />
          <Field
            label={t('form.expires')}
            name="expiresAt"
            type="datetime-local"
            hint={t('form.expires.hint')}
            value={expiresAt}
            onChange={(e) => setExpiresAt(e.target.value)}
            error={error?.fields.expiresAt}
          />
        </>
      )}
      <div className="modal-foot" style={{ padding: 0 }}>
        <Button variant="secondary" onClick={onClose}>
          {t('common.cancel')}
        </Button>
        <Button type="submit" loading={busy} disabled={url.trim() === ''}>
          {t('form.create')}
        </Button>
      </div>
    </form>
  );
}

// ===========================
//  Sửa đích
// ===========================

interface LinkModalProps {
  link: UrlResponse | null;
  onClose: () => void;
  /** Link đã đổi (bản mới) hoặc bị xoá (null). */
  onChanged: (updated: UrlResponse | null) => void;
}

export function EditLinkModal({ link, onClose, onChanged }: LinkModalProps) {
  const { t } = useI18n();
  return (
    <Modal open={link !== null} onClose={onClose} title={t('modal.edit.title')} description={t('modal.edit.hint')}>
      {link && <EditForm link={link} onClose={onClose} onChanged={onChanged} />}
    </Modal>
  );
}

function EditForm({ link, onClose, onChanged }: { link: UrlResponse } & Omit<LinkModalProps, 'link'>) {
  const { t } = useI18n();
  const toast = useToast();
  const { busy, error, run } = useSubmit();
  const [url, setUrl] = useState(link.originalUrl);
  const [expiresAt, setExpiresAt] = useState(toInputValue(link.expiresAt));

  async function submit(e: FormEvent) {
    e.preventDefault();
    let updated: UrlResponse | undefined;
    const ok = await run(async () => {
      updated = await updateUrl(link.shortCode, { originalUrl: url.trim(), expiresAt: fromInputValue(expiresAt) });
    });
    if (ok && updated) {
      toast.success(t('toast.updated'));
      onChanged(updated);
      onClose();
    }
  }

  return (
    <form className="form" onSubmit={submit} noValidate>
      <ErrorBanner message={error?.message} />
      <Field
        label={t('form.url')}
        name="originalUrl"
        type="url"
        value={url}
        onChange={(e) => setUrl(e.target.value)}
        error={error?.fields.originalUrl}
        autoFocus
      />
      <Field
        label={t('form.expires')}
        name="expiresAt"
        type="datetime-local"
        hint={t('form.expires.hint')}
        value={expiresAt}
        onChange={(e) => setExpiresAt(e.target.value)}
        error={error?.fields.expiresAt}
      />
      {expiresAt && (
        <button type="button" className="link-toggle" onClick={() => setExpiresAt('')}>
          {t('modal.edit.clearExpiry')}
        </button>
      )}
      <div className="modal-foot" style={{ padding: 0 }}>
        <Button variant="secondary" onClick={onClose}>
          {t('common.cancel')}
        </Button>
        <Button type="submit" loading={busy} disabled={url.trim() === ''}>
          {t('common.save')}
        </Button>
      </div>
    </form>
  );
}

// ===========================
//  Mật khẩu
// ===========================

export function PasswordModal({ link, onClose, onChanged }: LinkModalProps) {
  const { t } = useI18n();
  return (
    <Modal
      open={link !== null}
      onClose={onClose}
      title={t('modal.password.title')}
      description={link?.passwordProtected ? t('modal.password.on') : t('modal.password.off')}
    >
      {link && <PasswordForm link={link} onClose={onClose} onChanged={onChanged} />}
    </Modal>
  );
}

function PasswordForm({ link, onClose, onChanged }: { link: UrlResponse } & Omit<LinkModalProps, 'link'>) {
  const { t } = useI18n();
  const toast = useToast();
  const { busy, error, run } = useSubmit();
  const [password, setPassword] = useState('');

  async function apply(action: () => Promise<UrlResponse>, message: string) {
    let updated: UrlResponse | undefined;
    const ok = await run(async () => {
      updated = await action();
    });
    if (ok && updated) {
      toast.success(message);
      onChanged(updated);
      onClose();
    }
  }

  return (
    <form
      className="form"
      noValidate
      onSubmit={(e) => {
        e.preventDefault();
        void apply(() => setLinkPassword(link.shortCode, password), t('toast.passwordSet'));
      }}
    >
      <ErrorBanner message={error?.message} />
      <Field
        label={t('modal.password.new')}
        name="password"
        type="password"
        autoComplete="new-password"
        hint={t('form.password.hint')}
        value={password}
        onChange={(e) => setPassword(e.target.value)}
        error={error?.fields.password}
        autoFocus
      />
      <div className="modal-foot" style={{ padding: 0 }}>
        {link.passwordProtected && (
          <Button
            variant="ghost"
            className="btn-danger-text"
            style={{ marginRight: 'auto', color: 'var(--danger)' }}
            disabled={busy}
            onClick={() => void apply(() => removeLinkPassword(link.shortCode), t('toast.passwordRemoved'))}
          >
            {t('modal.password.remove')}
          </Button>
        )}
        <Button variant="secondary" onClick={onClose}>
          {t('common.cancel')}
        </Button>
        <Button type="submit" loading={busy} disabled={password === ''}>
          {t('modal.password.set')}
        </Button>
      </div>
    </form>
  );
}

// ===========================
//  Mã QR
// ===========================

export function QrModal({ link, onClose }: { link: UrlResponse | null; onClose: () => void }) {
  const { t } = useI18n();
  return (
    <Modal open={link !== null} onClose={onClose} title={t('modal.qr.title')} description={t('modal.qr.hint')}>
      {link && <QrBody link={link} onClose={onClose} />}
    </Modal>
  );
}

function QrBody({ link, onClose }: { link: UrlResponse; onClose: () => void }) {
  const { t } = useI18n();
  const [src, setSrc] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    let objectUrl: string | null = null;
    getQrPng(link.shortCode, 480)
      .then((blob) => {
        if (cancelled) return;
        objectUrl = URL.createObjectURL(blob);
        setSrc(objectUrl);
      })
      .catch(() => !cancelled && setSrc(null));
    return () => {
      cancelled = true;
      if (objectUrl) URL.revokeObjectURL(objectUrl); // tránh rò bộ nhớ khi đóng hộp thoại
    };
  }, [link.shortCode]);

  return (
    <>
      <div className="qr-box">
        {src ? (
          <img src={src} alt={t('modal.qr.alt', { url: link.shortUrl })} width={240} height={240} />
        ) : (
          <div className="skeleton" style={{ width: 240, height: 240 }} aria-label={t('common.loading')} />
        )}
      </div>
      <p className="muted small break" style={{ textAlign: 'center', marginTop: 12 }}>
        {link.shortUrl}
      </p>
      <div className="modal-foot" style={{ padding: '16px 0 0' }}>
        <Button variant="secondary" onClick={onClose}>
          {t('common.close')}
        </Button>
        {src && (
          <a className={buttonClass({})} href={src} download={`qr-${link.shortCode}.png`}>
            <Download size={16} aria-hidden="true" />
            {t('modal.qr.download')}
          </a>
        )}
      </div>
    </>
  );
}

// ===========================
//  Xoá
// ===========================

export function DeleteLinkModal({ link, onClose, onChanged }: LinkModalProps) {
  const { t } = useI18n();
  return (
    <Modal open={link !== null} onClose={onClose} title={t('modal.delete.title')}>
      {link && <DeleteBody link={link} onClose={onClose} onChanged={onChanged} />}
    </Modal>
  );
}

function DeleteBody({ link, onClose, onChanged }: { link: UrlResponse } & Omit<LinkModalProps, 'link'>) {
  const { t } = useI18n();
  const toast = useToast();
  const { busy, error, run } = useSubmit();

  async function confirm() {
    const ok = await run(() => deleteUrl(link.shortCode));
    if (ok) {
      toast.success(t('toast.deleted'));
      onChanged(null);
      onClose();
    }
  }

  return (
    <div className="form">
      <ErrorBanner message={error?.message} />
      <p className="muted">{t('modal.delete.text', { url: link.shortUrl })}</p>
      <div className="modal-foot" style={{ padding: 0 }}>
        <Button variant="secondary" onClick={onClose}>
          {t('common.cancel')}
        </Button>
        <Button variant="danger" loading={busy} onClick={confirm}>
          {t('modal.delete.confirm')}
        </Button>
      </div>
    </div>
  );
}
