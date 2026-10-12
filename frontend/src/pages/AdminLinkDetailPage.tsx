import { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { AlertCircle, ChevronRight, ExternalLink, Lock, ShieldBan, ShieldCheck } from 'lucide-react';
import { getAdminStats, getAdminUrl } from '../api/admin';
import { ApiError } from '../api/client';
import { toErrorView } from '../api/errors';
import type { AdminUrl, UrlStats } from '../api/types';
import { BlockModal, UnblockModal } from '../components/AdminLinkModals';
import { StatusBadge } from '../components/LinkActions';
import { DateRangeBar, KpiSkeleton, StatsBody, useStatsRange } from '../components/StatsView';
import { Button, buttonClass } from '../components/ui/Button';
import { CopyButton } from '../components/ui/CopyButton';
import { EmptyState } from '../components/ui/EmptyState';
import { useDocumentTitle } from '../hooks/useDocumentTitle';
import { useI18n } from '../i18n/I18nProvider';
import { displayUrl } from '../utils/datetime';

/**
 * Quản trị: chi tiết và thống kê click của MỌI link (kể cả link ẩn danh và link của người khác, mà trang thống kê của chủ
 * link trả 404), kèm chặn / gỡ chặn ngay tại đây. Dùng API admin nên chỉ admin vào được (RequireAdmin + server kiểm tra).
 */
export function AdminLinkDetailPage() {
  const { code = '' } = useParams();
  const { t } = useI18n();
  const { time, range, setRange } = useStatsRange();
  const [link, setLink] = useState<AdminUrl | null>(null);
  const [notFound, setNotFound] = useState(false);
  const [stats, setStats] = useState<UrlStats | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [dialog, setDialog] = useState<'block' | 'unblock' | null>(null);
  useDocumentTitle(link ? displayUrl(link.shortUrl) : t('nav.admin.links'));

  useEffect(() => {
    let cancelled = false;
    setNotFound(false);
    getAdminUrl(code)
      .then((l) => !cancelled && setLink(l))
      .catch((e) => {
        if (cancelled) return;
        // Link không có hoặc đã xoá: 404 (admin cũng không xem được link đã xoá)
        if (e instanceof ApiError && e.status === 404) setNotFound(true);
        else setError(toErrorView(e, t).message);
      });
    return () => {
      cancelled = true;
    };
  }, [code, t]);

  useEffect(() => {
    // Ô ngày đang trống (người dùng đang sửa) hoặc chưa biết múi giờ máy chủ: chưa gọi API
    if (!range || !range.from || !range.to) return;
    let cancelled = false;
    setLoading(true);
    getAdminStats(code, range.from, range.to)
      .then((s) => {
        if (cancelled) return;
        setStats(s);
        setError(null);
      })
      .catch((e) => {
        if (cancelled) return;
        if (!(e instanceof ApiError && e.status === 404)) setError(toErrorView(e, t).message);
      })
      .finally(() => !cancelled && setLoading(false));
    return () => {
      cancelled = true;
    };
  }, [code, range, t]);

  const crumbs = (
    <nav className="crumbs" aria-label="Breadcrumb">
      <Link to="/admin/links">{t('nav.admin.links')}</Link>
      <ChevronRight size={14} aria-hidden="true" />
      <span className="truncate" aria-current="page">{code}</span>
    </nav>
  );

  if (notFound) {
    return (
      <div className="stack">
        {crumbs}
        <div className="card">
          <EmptyState
            title={t('detail.notFound.title')}
            text={t('adminDetail.notFound.text')}
            action={
              <Link to="/admin/links" className={buttonClass({ variant: 'secondary' })}>
                {t('common.back')}
              </Link>
            }
          />
        </div>
      </div>
    );
  }

  const creator = link ? (link.userId ? link.createdBy : t('admin.anonymous')) : '';

  return (
    <div className="stack">
      {crumbs}

      <section className="card hero-card">
        <div className="big-link">
          {link ? (
            <a href={link.shortUrl} target="_blank" rel="noopener noreferrer" className="break" style={{ color: 'var(--text)' }}>
              {displayUrl(link.shortUrl)}
            </a>
          ) : (
            <h1 className="break">{code}</h1>
          )}
          {link && <CopyButton text={link.shortUrl} />}
          {link && <StatusBadge status={link.status} />}
          {link?.passwordProtected && (
            <span className="badge badge-primary">
              <Lock size={12} aria-hidden="true" />
              {t('link.protected')}
            </span>
          )}
        </div>
        {link && (
          <>
            <div className="muted break" style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
              <ExternalLink size={14} aria-hidden="true" style={{ flex: 'none' }} />
              {link.originalUrl}
            </div>
            <div className="muted small">{t('adminDetail.by', { name: creator })}</div>
            {link.status === 'BLOCKED' && link.blockedReason && (
              <div className="alert" role="alert">
                <AlertCircle size={18} aria-hidden="true" />
                <span>{t('link.blocked', { reason: link.blockedReason })}</span>
              </div>
            )}
            <div className="row">
              {link.status === 'BLOCKED' ? (
                <Button variant="secondary" size="sm" onClick={() => setDialog('unblock')}>
                  <ShieldCheck size={16} aria-hidden="true" />
                  {t('admin.act.unblock')}
                </Button>
              ) : (
                <Button variant="secondary" size="sm" onClick={() => setDialog('block')}>
                  <ShieldBan size={16} aria-hidden="true" />
                  {t('admin.act.block')}
                </Button>
              )}
            </div>
          </>
        )}
      </section>

      <DateRangeBar range={range} onChange={setRange} lastDays={time.lastDays} />

      {error && (
        <div className="alert" role="alert">
          <AlertCircle size={18} aria-hidden="true" />
          <span>{error}</span>
        </div>
      )}

      {loading && !stats && <KpiSkeleton />}

      {stats && <StatsBody stats={stats} loading={loading} />}

      {link && time.ready && (
        <section className="card card-pad" aria-labelledby="admin-details-title">
          <h2 id="admin-details-title" style={{ marginBottom: 16 }}>{t('detail.info.title')}</h2>
          <dl className="details-list">
            <dt>{t('detail.info.destination')}</dt>
            <dd>{link.originalUrl}</dd>
            <dt>{t('admin.col.creator')}</dt>
            <dd>{creator}</dd>
            <dt>{t('detail.info.created')}</dt>
            <dd>{time.format(link.createdAt)}</dd>
            <dt>{t('detail.info.updated')}</dt>
            <dd>{time.format(link.updatedAt)}</dd>
            <dt>{t('adminDetail.updatedBy')}</dt>
            <dd>{link.updatedBy}</dd>
            <dt>{t('detail.info.expires')}</dt>
            <dd>{link.expiresAt ? time.format(link.expiresAt) : t('link.neverExpires')}</dd>
            <dt>{t('detail.info.password')}</dt>
            <dd>{link.passwordProtected ? t('detail.info.on') : t('detail.info.off')}</dd>
          </dl>
        </section>
      )}

      <BlockModal link={dialog === 'block' ? link : null} onClose={() => setDialog(null)} onChanged={setLink} />
      <UnblockModal link={dialog === 'unblock' ? link : null} onClose={() => setDialog(null)} onChanged={setLink} />
    </div>
  );
}
