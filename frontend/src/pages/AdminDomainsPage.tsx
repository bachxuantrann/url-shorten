import { useCallback, useState, type FormEvent } from 'react';
import { Globe, Plus, Trash2 } from 'lucide-react';
import { addBlockedDomain, listBlockedDomains, removeBlockedDomain } from '../api/admin';
import type { BlockedDomain } from '../api/types';
import { AdminHead, ListSkeleton, LoadError, Pager, SearchBox } from '../components/admin';
import { Field } from '../components/Field';
import { Button } from '../components/ui/Button';
import { EmptyState } from '../components/ui/EmptyState';
import { Modal } from '../components/ui/Modal';
import { useToast } from '../components/ui/Toast';
import { useDocumentTitle } from '../hooks/useDocumentTitle';
import { useMediaQuery } from '../hooks/useMediaQuery';
import { usePagedList } from '../hooks/usePagedList';
import { useServerTime } from '../hooks/useServerTime';
import { useSubmit } from '../hooks/useSubmit';
import { useI18n } from '../i18n/I18nProvider';
import { useDebounced } from '../utils/useDebounced';

const PAGE_SIZE = 10;

/** Quản trị: danh sách domain bị cấm (cả subdomain). Thêm có thể chặn luôn các link hiện có; gỡ không gỡ chặn link cũ. */
export function AdminDomainsPage() {
  const { t, language } = useI18n();
  useDocumentTitle(t('nav.admin.domains'));
  const compact = useMediaQuery('(max-width: 760px)');
  const { ready: zoneReady } = useServerTime();

  const [keyword, setKeyword] = useState('');
  const [page, setPage] = useState(0);
  const [adding, setAdding] = useState(false);
  const [removing, setRemoving] = useState<BlockedDomain | null>(null);

  const debouncedKeyword = useDebounced(keyword.trim());
  const load = useCallback(
    () => listBlockedDomains({ keyword: debouncedKeyword || undefined, page, size: PAGE_SIZE }),
    [debouncedKeyword, page],
  );
  const { data, loading, error, reload } = usePagedList(load, () => setPage((p) => Math.max(p - 1, 0)));
  const content = data?.content ?? [];

  const addButton = (
    <Button onClick={() => setAdding(true)}>
      <Plus size={18} aria-hidden="true" />
      {t('admin.domains.add')}
    </Button>
  );

  return (
    <div className="stack">
      <AdminHead
        title={t('admin.domains.title')}
        sub={data ? t('admin.domains.count', { count: data.totalElements.toLocaleString(language) }) : undefined}
        actions={addButton}
      />

      <section className="card" aria-label={t('admin.domains.title')}>
        <div className="toolbar">
          <SearchBox
            label={t('admin.domains.search')}
            value={keyword}
            onChange={(v) => {
              setKeyword(v);
              setPage(0);
            }}
          />
        </div>

        {error && <LoadError message={error} onRetry={reload} />}

        {(loading && !data) || !zoneReady ? (
          <ListSkeleton />
        ) : data && content.length === 0 ? (
          debouncedKeyword ? (
            <EmptyState title={t('links.emptyFiltered.title')} text={t('links.emptyFiltered.text')} />
          ) : (
            <EmptyState title={t('admin.domains.empty.title')} text={t('admin.domains.empty.text')} action={addButton} />
          )
        ) : (
          data && (
            <div aria-busy={loading}>
              {compact ? (
                <div className="cards-mobile">
                  {content.map((d) => (
                    <DomainCard key={d.id} domain={d} onRemove={() => setRemoving(d)} />
                  ))}
                </div>
              ) : (
                <div className="table-scroll">
                  <table className="data-table">
                    <thead>
                      <tr>
                        <th scope="col">{t('admin.domains.col.domain')}</th>
                        <th scope="col">{t('admin.domains.col.reason')}</th>
                        <th scope="col" className="col-created">{t('admin.domains.col.added')}</th>
                        <th scope="col" className="col-action">
                          <span className="sr-only">{t('admin.col.actions')}</span>
                        </th>
                      </tr>
                    </thead>
                    <tbody>
                      {content.map((d) => (
                        <DomainRow key={d.id} domain={d} onRemove={() => setRemoving(d)} />
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
              <Pager data={data} count={content.length} onPage={setPage} />
            </div>
          )
        )}
      </section>

      <AddDomainModal open={adding} onClose={() => setAdding(false)} onAdded={() => { setPage(0); reload(); }} />
      <RemoveDomainModal domain={removing} onClose={() => setRemoving(null)} onRemoved={reload} />
    </div>
  );
}

interface ItemProps {
  domain: BlockedDomain;
  onRemove: () => void;
}

function RemoveButton({ domain, onRemove }: ItemProps) {
  const { t } = useI18n();
  return (
    <Button variant="secondary" size="sm" onClick={onRemove} aria-label={`${t('admin.domains.remove')} ${domain.domain}`}>
      <Trash2 size={16} aria-hidden="true" />
      {t('admin.domains.remove')}
    </Button>
  );
}

function Reason({ domain }: { domain: BlockedDomain }) {
  const { t } = useI18n();
  return domain.reason ? <span>{domain.reason}</span> : <span className="muted">{t('admin.domains.noReason')}</span>;
}

function DomainRow({ domain, onRemove }: ItemProps) {
  const time = useServerTime();
  return (
    <tr>
      <td>
        <div className="row" style={{ gap: 10, flexWrap: 'nowrap' }}>
          <Globe size={18} aria-hidden="true" style={{ flex: 'none' }} />
          <strong className="truncate">{domain.domain}</strong>
        </div>
      </td>
      <td className="small">
        <Reason domain={domain} />
      </td>
      <td className="muted small">{time.formatDay(domain.createdAt)}</td>
      <td className="col-actions">
        <RemoveButton domain={domain} onRemove={onRemove} />
      </td>
    </tr>
  );
}

function DomainCard({ domain, onRemove }: ItemProps) {
  const time = useServerTime();
  return (
    <div className="link-card">
      <div className="row" style={{ gap: 10, flexWrap: 'nowrap' }}>
        <Globe size={18} aria-hidden="true" style={{ flex: 'none' }} />
        <strong className="truncate">{domain.domain}</strong>
      </div>
      <div className="meta">
        <Reason domain={domain} />
        <span>{time.formatDay(domain.createdAt)}</span>
      </div>
      <div className="actions">
        <RemoveButton domain={domain} onRemove={onRemove} />
      </div>
    </div>
  );
}

// ===========================
//  Thêm domain
// ===========================

interface AddProps {
  open: boolean;
  onClose: () => void;
  onAdded: () => void;
}

function AddDomainModal({ open, onClose, onAdded }: AddProps) {
  const { t } = useI18n();
  return (
    <Modal open={open} onClose={onClose} title={t('admin.domains.add')} description={t('admin.domains.add.sub')}>
      <AddForm onClose={onClose} onAdded={onAdded} />
    </Modal>
  );
}

function AddForm({ onClose, onAdded }: Omit<AddProps, 'open'>) {
  const { t } = useI18n();
  const toast = useToast();
  const { busy, error, run } = useSubmit();
  const [domain, setDomain] = useState('');
  const [reason, setReason] = useState('');
  const [blockExisting, setBlockExisting] = useState(true);

  async function submit(e: FormEvent) {
    e.preventDefault();
    let added: BlockedDomain | undefined;
    const ok = await run(async () => {
      added = await addBlockedDomain({ domain: domain.trim(), reason: reason.trim() || undefined, blockExistingLinks: blockExisting });
    });
    if (ok && added) {
      const count = added.blockedLinks ?? 0;
      toast.success(count > 0 ? t('toast.domainAddedLinks', { count }) : t('toast.domainAdded'));
      onAdded();
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
      <Field
        label={t('admin.domains.field.domain')}
        name="domain"
        placeholder={t('admin.domains.field.domain.placeholder')}
        autoComplete="off"
        value={domain}
        maxLength={253}
        onChange={(e) => setDomain(e.target.value)}
        error={error?.fields.domain}
        autoFocus
        required
      />
      <Field
        label={t('admin.domains.field.reason')}
        name="reason"
        labelSuffix={<span className="muted"> ({t('common.optional')})</span>}
        value={reason}
        maxLength={255}
        onChange={(e) => setReason(e.target.value)}
        error={error?.fields.reason}
      />
      <label className="row" style={{ gap: 10, alignItems: 'center' }}>
        <input type="checkbox" checked={blockExisting} onChange={(e) => setBlockExisting(e.target.checked)} />
        <span>{t('admin.domains.field.existing')}</span>
      </label>
      <div className="modal-foot" style={{ padding: 0 }}>
        <Button variant="secondary" onClick={onClose}>
          {t('common.cancel')}
        </Button>
        <Button type="submit" variant="danger" loading={busy} disabled={domain.trim() === ''}>
          {t('admin.domains.add.confirm')}
        </Button>
      </div>
    </form>
  );
}

// ===========================
//  Gỡ domain
// ===========================

function RemoveDomainModal({ domain, onClose, onRemoved }: { domain: BlockedDomain | null; onClose: () => void; onRemoved: () => void }) {
  const { t } = useI18n();
  return (
    <Modal open={domain !== null} onClose={onClose} title={domain ? t('admin.domains.remove.title', { domain: domain.domain }) : ''}>
      {domain && <RemoveBody domain={domain} onClose={onClose} onRemoved={onRemoved} />}
    </Modal>
  );
}

function RemoveBody({ domain, onClose, onRemoved }: { domain: BlockedDomain; onClose: () => void; onRemoved: () => void }) {
  const { t } = useI18n();
  const toast = useToast();
  const { busy, error, run } = useSubmit();

  async function confirm() {
    const ok = await run(() => removeBlockedDomain(domain.id));
    if (ok) {
      toast.success(t('toast.domainRemoved'));
      onRemoved();
      onClose();
    }
  }

  return (
    <div className="form">
      {error?.message && (
        <div className="alert" role="alert">
          <span>{error.message}</span>
        </div>
      )}
      <p className="muted">{t('admin.domains.remove.text')}</p>
      <div className="modal-foot" style={{ padding: 0 }}>
        <Button variant="secondary" onClick={onClose}>
          {t('common.cancel')}
        </Button>
        <Button variant="danger" loading={busy} onClick={confirm}>
          {t('admin.domains.remove.confirm')}
        </Button>
      </div>
    </div>
  );
}
