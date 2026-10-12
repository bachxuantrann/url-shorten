import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { AlertCircle, ExternalLink, Link2, Lock, Plus, Search, X } from 'lucide-react';
import { toErrorView } from '../api/errors';
import type { PageResponse, UrlResponse, UrlStatus } from '../api/types';
import { listUrls, type SortBy } from '../api/urls';
import { useCreateLink } from '../components/AppLayout';
import { BlockedNotice, LinkMenu, StatusBadge, useLinkModals } from '../components/LinkActions';
import { Button } from '../components/ui/Button';
import { CopyButton } from '../components/ui/CopyButton';
import { EmptyState } from '../components/ui/EmptyState';
import { Select } from '../components/ui/Select';
import { useDocumentTitle } from '../hooks/useDocumentTitle';
import { useMediaQuery } from '../hooks/useMediaQuery';
import { useI18n } from '../i18n/I18nProvider';
import type { MessageKey } from '../i18n/messages';
import { useServerTime } from '../hooks/useServerTime';
import { displayUrl } from '../utils/datetime';
import { useDebounced } from '../utils/useDebounced';

const PAGE_SIZE = 10;
const STATUSES: UrlStatus[] = ['ACTIVE', 'DISABLED', 'EXPIRED', 'BLOCKED'];
const SORTS: SortBy[] = ['createdAt', 'clickCount', 'expiresAt'];

/** Danh sách link của người dùng: tìm kiếm, lọc theo trạng thái, sắp xếp, phân trang; bảng trên máy tính, thẻ trên điện thoại. */
export function LinksPage() {
  const { t, language } = useI18n();
  useDocumentTitle(t('links.title'));
  const { openCreate, createdVersion } = useCreateLink();
  const compact = useMediaQuery('(max-width: 1100px)');
  // Giờ trong danh sách phải được quy đổi theo múi giờ máy chủ: đợi biết múi giờ để không hiện giờ chưa quy đổi (sai) thoáng qua
  const { ready: zoneReady } = useServerTime();

  const [keyword, setKeyword] = useState('');
  const [status, setStatus] = useState<UrlStatus | ''>('');
  const [sortBy, setSortBy] = useState<SortBy>('createdAt');
  const [page, setPage] = useState(0);
  const [data, setData] = useState<PageResponse<UrlResponse> | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  // Tăng để buộc tải lại (sau khi xoá, thử lại sau lỗi)
  const [reloadKey, setReloadKey] = useState(0);

  const debouncedKeyword = useDebounced(keyword.trim());

  // Link vừa tạo nằm đầu danh sách mới-nhất: về trang đầu, bỏ bộ lọc để chắc chắn thấy nó
  useEffect(() => {
    if (createdVersion === 0) return;
    setKeyword('');
    setStatus('');
    setSortBy('createdAt');
    setPage(0);
    setReloadKey((k) => k + 1);
  }, [createdVersion]);

  useEffect(() => {
    let cancelled = false; // bỏ kết quả của request cũ nếu bộ lọc đã đổi trong lúc chờ
    setLoading(true);
    listUrls({
      keyword: debouncedKeyword || undefined,
      status: status || undefined,
      page,
      size: PAGE_SIZE,
      sortBy,
      sortDir: 'desc',
    })
      .then((res) => {
        if (cancelled) return;
        // Xoá link cuối của trang cuối làm trang hiện tại rỗng: lùi về trang trước
        if (res.content.length === 0 && page > 0) setPage(page - 1);
        else {
          setData(res);
          setError(null);
        }
      })
      .catch((e) => !cancelled && setError(toErrorView(e, t).message))
      .finally(() => !cancelled && setLoading(false));
    return () => {
      cancelled = true;
    };
  }, [debouncedKeyword, status, sortBy, page, reloadKey, t]);

  const onChanged = useCallback((updated: UrlResponse | null, from: UrlResponse) => {
    if (updated === null) {
      setReloadKey((k) => k + 1);
      return;
    }
    setData((d) => d && { ...d, content: d.content.map((l) => (l.shortCode === from.shortCode ? updated : l)) });
  }, []);

  const { open, modals } = useLinkModals(onChanged);

  const filtered = debouncedKeyword !== '' || status !== '';
  const clearFilters = () => {
    setKeyword('');
    setStatus('');
    setPage(0);
  };

  const content = data?.content ?? [];
  const from = data && data.totalElements > 0 ? data.page * data.size + 1 : 0;
  const to = data ? data.page * data.size + content.length : 0;

  return (
    <div className="stack">
      <div className="page-head">
        <div>
          <h1>{t('links.title')}</h1>
          {data && <p>{t('links.count', { count: data.totalElements.toLocaleString(language) })}</p>}
        </div>
      </div>

      <section className="card" aria-label={t('links.title')}>
        <div className="toolbar">
          <div className="input-wrap">
            <Search className="input-icon" size={18} aria-hidden="true" />
            <input
              type="search"
              className="input with-icon"
              aria-label={t('links.search')}
              placeholder={t('links.search')}
              value={keyword}
              maxLength={100}
              onChange={(e) => {
                setKeyword(e.target.value);
                setPage(0);
              }}
            />
            {keyword && (
              <button
                type="button"
                className="btn btn-ghost btn-icon btn-sm input-trailing"
                aria-label={t('links.clearSearch')}
                onClick={() => setKeyword('')}
              >
                <X size={16} aria-hidden="true" />
              </button>
            )}
          </div>
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

        {error && (
          <div style={{ padding: 16 }}>
            <div className="alert" role="alert">
              <AlertCircle size={18} aria-hidden="true" />
              <span>{t('links.error')} {error}</span>
              <Button variant="secondary" size="sm" style={{ marginLeft: 'auto' }} onClick={() => setReloadKey((k) => k + 1)}>
                {t('common.retry')}
              </Button>
            </div>
          </div>
        )}

        {(loading && !data) || !zoneReady ? (
          <Skeleton />
        ) : data && content.length === 0 ? (
          filtered ? (
            <EmptyState
              title={t('links.emptyFiltered.title')}
              text={t('links.emptyFiltered.text')}
              action={
                <Button variant="secondary" onClick={clearFilters}>
                  {t('links.clearFilters')}
                </Button>
              }
            />
          ) : (
            <EmptyState
              title={t('links.empty.title')}
              text={t('links.empty.text')}
              action={
                <Button onClick={openCreate}>
                  <Plus size={18} aria-hidden="true" />
                  {t('links.create')}
                </Button>
              }
            />
          )
        ) : (
          data && (
            <div aria-busy={loading}>
              {compact ? (
                <div className="cards-mobile">
                  {content.map((link) => (
                    <LinkCard key={link.shortCode} link={link} open={open} onChanged={onChanged} />
                  ))}
                </div>
              ) : (
                <div className="table-scroll">
                <table className="link-table">
                  <thead>
                    <tr>
                      <th scope="col">{t('links.col.link')}</th>
                      <th scope="col" className="col-clicks">{t('links.col.clicks')}</th>
                      <th scope="col" className="col-status">{t('links.col.status')}</th>
                      <th scope="col" className="col-created">{t('links.col.created')}</th>
                      <th scope="col" className="col-actions"><span className="sr-only">{t('act.more')}</span></th>
                    </tr>
                  </thead>
                  <tbody>
                    {content.map((link) => (
                      <LinkRow key={link.shortCode} link={link} open={open} onChanged={onChanged} />
                    ))}
                  </tbody>
                </table>
                </div>
              )}
              {data.totalPages > 1 && (
                <div className="pager">
                  <span>{t('links.showing', { from, to, total: data.totalElements })}</span>
                  <div className="row" style={{ gap: 8 }}>
                    <Button variant="secondary" size="sm" disabled={!data.hasPrevious} onClick={() => setPage(page - 1)}>
                      {t('links.prev')}
                    </Button>
                    <Button variant="secondary" size="sm" disabled={!data.hasNext} onClick={() => setPage(page + 1)}>
                      {t('links.next')}
                    </Button>
                  </div>
                </div>
              )}
            </div>
          )
        )}
      </section>
      {modals}
    </div>
  );

  function Skeleton() {
    return (
      <div aria-busy="true" aria-label={t('common.loading')} style={{ padding: 16, display: 'flex', flexDirection: 'column', gap: 18 }}>
        {[0, 1, 2, 3].map((i) => (
          <div key={i} className="row" style={{ gap: 14, flexWrap: 'nowrap' }}>
            <div className="skeleton" style={{ width: 38, height: 38, borderRadius: 10, flex: 'none' }} />
            <div style={{ flex: 1 }}>
              <div className="skeleton" style={{ height: 14, width: '32%', marginBottom: 8 }} />
              <div className="skeleton" style={{ height: 12, width: '58%' }} />
            </div>
          </div>
        ))}
      </div>
    );
  }
}

interface RowProps {
  link: UrlResponse;
  open: ReturnType<typeof useLinkModals>['open'];
  onChanged: (updated: UrlResponse | null, from: UrlResponse) => void;
}

/** Phần "link + đích" dùng chung cho dòng bảng và thẻ điện thoại. */
function LinkIdentity({ link }: { link: UrlResponse }) {
  const { t } = useI18n();
  return (
    <div className="link-cell">
      <span className="link-avatar" aria-hidden="true">
        <Link2 size={18} />
      </span>
      <div className="link-main">
        <div className="link-short">
          <Link to={`/links/${encodeURIComponent(link.shortCode)}`} className="truncate">
            {displayUrl(link.shortUrl)}
          </Link>
          <CopyButton text={link.shortUrl} />
          <a
            href={link.shortUrl}
            target="_blank"
            rel="noopener noreferrer"
            className="btn btn-ghost btn-icon btn-sm"
            aria-label={t('link.openShort')}
            title={t('link.openShort')}
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

function LinkRow({ link, open, onChanged }: RowProps) {
  const { t, language } = useI18n();
  const time = useServerTime();
  return (
    <tr>
      <td>
        <LinkIdentity link={link} />
        <BlockedNotice link={link} open={open} />
      </td>
      <td className="num">{link.clickCount.toLocaleString(language)}</td>
      <td>
        <div className="row" style={{ gap: 6 }}>
          <StatusBadge status={link.status} />
          {link.passwordProtected && (
            <span className="badge badge-primary">
              <Lock size={12} aria-hidden="true" />
              {t('link.protected')}
            </span>
          )}
        </div>
        <div className="muted small" style={{ marginTop: 4 }}>
          {link.expiresAt ? t('link.expires', { date: time.format(link.expiresAt) }) : t('link.neverExpires')}
        </div>
      </td>
      <td className="muted small">{time.formatDay(link.createdAt)}</td>
      <td className="col-actions">
        <LinkMenu link={link} open={open} onChanged={onChanged} />
      </td>
    </tr>
  );
}

function LinkCard({ link, open, onChanged }: RowProps) {
  const { t, language } = useI18n();
  const time = useServerTime();
  return (
    <div className="link-card">
      <LinkIdentity link={link} />
      <div className="meta">
        <StatusBadge status={link.status} />
        {link.passwordProtected && (
          <span className="badge badge-primary">
            <Lock size={12} aria-hidden="true" />
            {t('link.protected')}
          </span>
        )}
        <span>{t('link.clicks', { count: link.clickCount.toLocaleString(language) })}</span>
        <span>{link.expiresAt ? t('link.expires', { date: time.format(link.expiresAt) }) : t('link.neverExpires')}</span>
      </div>
      <BlockedNotice link={link} open={open} />
      <div className="actions">
        <Link to={`/links/${encodeURIComponent(link.shortCode)}`} className="btn btn-secondary btn-sm">
          {t('act.stats')}
        </Link>
        <LinkMenu link={link} open={open} onChanged={onChanged} hideStats />
      </div>
    </div>
  );
}
