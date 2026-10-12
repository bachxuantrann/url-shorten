import { useCallback, useState } from 'react';
import { Link } from 'react-router-dom';
import { ExternalLink, Link2, Lock, ShieldBan, ShieldCheck } from 'lucide-react';
import { listAdminUrls } from '../api/admin';
import type { AdminUrl, UrlStatus } from '../api/types';
import type { SortBy } from '../api/urls';
import { AdminHead, ListSkeleton, LoadError, Pager, SearchBox } from '../components/admin';
import { BlockModal, UnblockModal } from '../components/AdminLinkModals';
import { StatusBadge } from '../components/LinkActions';
import { Button } from '../components/ui/Button';
import { CopyButton } from '../components/ui/CopyButton';
import { EmptyState } from '../components/ui/EmptyState';
import { Select } from '../components/ui/Select';
import { useDocumentTitle } from '../hooks/useDocumentTitle';
import { useMediaQuery } from '../hooks/useMediaQuery';
import { usePagedList } from '../hooks/usePagedList';
import { useServerTime } from '../hooks/useServerTime';
import { useI18n } from '../i18n/I18nProvider';
import type { MessageKey } from '../i18n/messages';
import { displayUrl } from '../utils/datetime';
import { useDebounced } from '../utils/useDebounced';

const PAGE_SIZE = 10;
const STATUSES: UrlStatus[] = ['ACTIVE', 'DISABLED', 'EXPIRED', 'BLOCKED'];
const SORTS: SortBy[] = ['createdAt', 'clickCount', 'expiresAt'];

/** Quản trị: mọi link của mọi người (kể cả ẩn danh), tìm theo link/mã/người tạo, chặn và gỡ chặn. */
export function AdminLinksPage() {
  const { t, language } = useI18n();
  useDocumentTitle(t('nav.admin.links'));
  const compact = useMediaQuery('(max-width: 1100px)');
  const { ready: zoneReady } = useServerTime();

  const [keyword, setKeyword] = useState('');
  const [status, setStatus] = useState<UrlStatus | ''>('');
  const [sortBy, setSortBy] = useState<SortBy>('createdAt');
  const [page, setPage] = useState(0);
  const [target, setTarget] = useState<{ kind: 'block' | 'unblock'; link: AdminUrl } | null>(null);

  const debouncedKeyword = useDebounced(keyword.trim());
  const load = useCallback(
    () => listAdminUrls({ keyword: debouncedKeyword || undefined, status: status || undefined, page, size: PAGE_SIZE, sortBy, sortDir: 'desc' }),
    [debouncedKeyword, status, page, sortBy],
  );
  const { data, setData, loading, error, reload } = usePagedList(load, () => setPage((p) => Math.max(p - 1, 0)));

  const filtered = debouncedKeyword !== '' || status !== '';
  const content = data?.content ?? [];

  const replace = (updated: AdminUrl) =>
    setData((d) => d && { ...d, content: d.content.map((l) => (l.shortCode === updated.shortCode ? updated : l)) });

  return (
    <div className="stack">
      <AdminHead title={t('nav.admin.links')} sub={data ? t('links.count', { count: data.totalElements.toLocaleString(language) }) : undefined} />

      <section className="card" aria-label={t('nav.admin.links')}>
        <div className="toolbar">
          <SearchBox
            label={t('admin.search.links')}
            value={keyword}
            onChange={(v) => {
              setKeyword(v);
              setPage(0);
            }}
          />
          <div className="segmented" role="group" aria-label={t('links.col.status')}>
            {(['', ...STATUSES] as (UrlStatus | '')[]).map((s) => (
              <button
                key={s || 'all'}
                type="button"
                aria-pressed={status === s}
                onClick={() => {
                  setStatus(s);
                  setPage(0);
                }}
              >
                {s === '' ? t('links.filter.all') : t(`links.filter.${s}` as MessageKey)}
              </button>
            ))}
          </div>
          <span className="spacer" />
          <Select
            prefix={`${t('links.sort')}:`}
            value={sortBy}
            options={SORTS.map((value) => ({ value, label: t(`links.sort.${value}` as MessageKey) }))}
            onChange={(value) => {
              setSortBy(value as SortBy);
              setPage(0);
            }}
          />
        </div>

        {error && <LoadError message={`${t('links.error')} ${error}`} onRetry={reload} />}

        {(loading && !data) || !zoneReady ? (
          <ListSkeleton />
        ) : data && content.length === 0 ? (
          filtered ? (
            <EmptyState
              title={t('links.emptyFiltered.title')}
              text={t('links.emptyFiltered.text')}
              action={
                <Button
                  variant="secondary"
                  onClick={() => {
                    setKeyword('');
                    setStatus('');
                    setPage(0);
                  }}
                >
                  {t('links.clearFilters')}
                </Button>
              }
            />
          ) : (
            <EmptyState title={t('admin.empty.links.title')} text={t('admin.empty.links.text')} />
          )
        ) : (
          data && (
            <div aria-busy={loading}>
              {compact ? (
                <div className="cards-mobile">
                  {content.map((link) => (
                    <LinkCard key={link.shortCode} link={link} onAction={(kind) => setTarget({ kind, link })} />
                  ))}
                </div>
              ) : (
                <div className="table-scroll">
                  <table className="link-table">
                    <thead>
                      <tr>
                        <th scope="col">{t('links.col.link')}</th>
                        <th scope="col" className="col-creator">{t('admin.col.creator')}</th>
                        <th scope="col" className="col-clicks">{t('links.col.clicks')}</th>
                        <th scope="col" className="col-status">{t('links.col.status')}</th>
                        <th scope="col" className="col-created">{t('links.col.created')}</th>
                        <th scope="col" className="col-action">
                          <span className="sr-only">{t('admin.col.actions')}</span>
                        </th>
                      </tr>
                    </thead>
                    <tbody>
                      {content.map((link) => (
                        <LinkRow key={link.shortCode} link={link} onAction={(kind) => setTarget({ kind, link })} />
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

      <BlockModal link={target?.kind === 'block' ? target.link : null} onClose={() => setTarget(null)} onChanged={replace} />
      <UnblockModal link={target?.kind === 'unblock' ? target.link : null} onClose={() => setTarget(null)} onChanged={replace} />
    </div>
  );
}

interface ItemProps {
  link: AdminUrl;
  onAction: (kind: 'block' | 'unblock') => void;
}

function Creator({ link }: { link: AdminUrl }) {
  const { t } = useI18n();
  // Link ẩn danh không có chủ: createdBy = "anonymous"
  return link.userId ? <span>{link.createdBy}</span> : <span className="badge">{t('admin.anonymous')}</span>;
}

function LinkIdentity({ link }: { link: AdminUrl }) {
  const { t } = useI18n();
  return (
    <div className="link-cell">
      <span className="link-avatar" aria-hidden="true">
        <Link2 size={18} />
      </span>
      <div className="link-main">
        <div className="link-short">
          <Link to={`/admin/links/${encodeURIComponent(link.shortCode)}`} className="truncate">
            {displayUrl(link.shortUrl)}
          </Link>
          <CopyButton text={link.shortUrl} />
          <a
            href={link.originalUrl}
            target="_blank"
            rel="noopener noreferrer"
            className="btn btn-ghost btn-icon btn-sm"
            aria-label={t('admin.link.open')}
            title={t('admin.link.open')}
          >
            <ExternalLink size={16} aria-hidden="true" />
          </a>
        </div>
        <div className="link-target truncate" title={link.originalUrl}>
          {link.originalUrl}
        </div>
      </div>
    </div>
  );
}

/** Chặn được mọi link chưa bị chặn; gỡ chặn chỉ với link đang bị chặn. (Link hết hạn/đã tắt vẫn chặn được.) */
function ActionButton({ link, onAction }: ItemProps) {
  const { t } = useI18n();
  return link.status === 'BLOCKED' ? (
    <Button variant="secondary" size="sm" onClick={() => onAction('unblock')} aria-label={`${t('admin.act.unblock')} ${link.shortCode}`}>
      <ShieldCheck size={16} aria-hidden="true" />
      {t('admin.act.unblock')}
    </Button>
  ) : (
    <Button variant="secondary" size="sm" onClick={() => onAction('block')} aria-label={`${t('admin.act.block')} ${link.shortCode}`}>
      <ShieldBan size={16} aria-hidden="true" />
      {t('admin.act.block')}
    </Button>
  );
}

function Badges({ link }: { link: AdminUrl }) {
  const { t } = useI18n();
  return (
    <div className="row" style={{ gap: 6 }}>
      <StatusBadge status={link.status} />
      {link.passwordProtected && (
        <span className="badge badge-primary">
          <Lock size={12} aria-hidden="true" />
          {t('link.protected')}
        </span>
      )}
    </div>
  );
}

function LinkRow({ link, onAction }: ItemProps) {
  const { t, language } = useI18n();
  const time = useServerTime();
  return (
    <tr>
      <td>
        <LinkIdentity link={link} />
        {link.status === 'BLOCKED' && link.blockedReason && (
          <div className="field-error" style={{ marginTop: 6 }}>
            {t('link.blocked', { reason: link.blockedReason })}
          </div>
        )}
      </td>
      <td className="small truncate" title={link.createdBy}>
        <Creator link={link} />
      </td>
      <td className="num">{link.clickCount.toLocaleString(language)}</td>
      <td>
        <Badges link={link} />
        <div className="muted small" style={{ marginTop: 4 }}>
          {link.expiresAt ? t('link.expires', { date: time.format(link.expiresAt) }) : t('link.neverExpires')}
        </div>
      </td>
      <td className="muted small">{time.formatDay(link.createdAt)}</td>
      <td className="col-actions">
        <ActionButton link={link} onAction={onAction} />
      </td>
    </tr>
  );
}

function LinkCard({ link, onAction }: ItemProps) {
  const { t, language } = useI18n();
  const time = useServerTime();
  return (
    <div className="link-card">
      <LinkIdentity link={link} />
      <div className="meta">
        <Badges link={link} />
        <Creator link={link} />
        <span>{t('link.clicks', { count: link.clickCount.toLocaleString(language) })}</span>
        <span>{link.expiresAt ? t('link.expires', { date: time.format(link.expiresAt) }) : t('link.neverExpires')}</span>
      </div>
      {link.status === 'BLOCKED' && link.blockedReason && (
        <div className="field-error" style={{ marginTop: 6 }}>
          {t('link.blocked', { reason: link.blockedReason })}
        </div>
      )}
      <div className="actions">
        <ActionButton link={link} onAction={onAction} />
      </div>
    </div>
  );
}
