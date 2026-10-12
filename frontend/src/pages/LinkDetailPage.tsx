import { useCallback, useEffect, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { ChevronRight, ExternalLink, Lock, QrCode } from 'lucide-react';
import { ApiError } from '../api/client';
import { toErrorView } from '../api/errors';
import { getStats, getUrl } from '../api/stats';
import type { UrlResponse, UrlStats } from '../api/types';
import { BlockedNotice, LinkMenu, StatusBadge, useLinkModals } from '../components/LinkActions';
import { DateRangeBar, KpiSkeleton, StatsBody, useStatsRange } from '../components/StatsView';
import { Button, buttonClass } from '../components/ui/Button';
import { CopyButton } from '../components/ui/CopyButton';
import { EmptyState } from '../components/ui/EmptyState';
import { useDocumentTitle } from '../hooks/useDocumentTitle';
import { useI18n } from '../i18n/I18nProvider';
import { displayUrl } from '../utils/datetime';
import { AlertCircle } from 'lucide-react';

/** Chi tiết một link của chủ link: thông tin, thao tác, khoảng ngày, thống kê click, biểu đồ và phân bổ. */
export function LinkDetailPage() {
  const { code = '' } = useParams();
  const navigate = useNavigate();
  const { t } = useI18n();
  // Mặc định 30 ngày gần nhất theo ngày của máy chủ: range là null cho tới khi biết múi giờ, lúc đó chưa gọi API
  const { time, range, setRange } = useStatsRange();
  const [link, setLink] = useState<UrlResponse | null>(null);
  const [notFound, setNotFound] = useState(false);
  const [stats, setStats] = useState<UrlStats | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  useDocumentTitle(link ? displayUrl(link.shortUrl) : t('links.title'));

  useEffect(() => {
    let cancelled = false;
    setNotFound(false);
    getUrl(code)
      .then((l) => !cancelled && setLink(l))
      .catch((e) => {
        if (cancelled) return;
        // Link của người khác, đã xoá hoặc không tồn tại đều là 404 (backend che khác biệt này)
        if (e instanceof ApiError && e.status === 404) setNotFound(true);
        else setError(toErrorView(e, t).message);
      });
    return () => {
      cancelled = true;
    };
  }, [code, t]);

  useEffect(() => {
    // Ô ngày đang trống (người dùng đang sửa): chưa gọi API
    if (!range || !range.from || !range.to) return;
    let cancelled = false;
    setLoading(true);
    getStats(code, range.from, range.to)
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

  const onChanged = useCallback(
    (updated: UrlResponse | null) => {
      // Link bị xoá: trang này không còn nghĩa, quay về danh sách
      if (updated === null) navigate('/dashboard');
      else setLink(updated);
    },
    [navigate],
  );
  const { open, modals } = useLinkModals(onChanged);

  const crumbs = (
    <nav className="crumbs" aria-label="Breadcrumb">
      <Link to="/dashboard">{t('detail.crumb')}</Link>
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
            text={t('detail.notFound.text')}
            action={
              <Link to="/dashboard" className={buttonClass({ variant: 'secondary' })}>
                {t('common.back')}
              </Link>
            }
          />
        </div>
      </div>
    );
  }

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
            {link.status === 'BLOCKED' && link.blockedReason && (
              <div className="alert" role="alert">
                <AlertCircle size={18} aria-hidden="true" />
                <span>{t('link.blocked', { reason: link.blockedReason })}</span>
              </div>
            )}
            <BlockedNotice link={link} open={open} showReason={false} />
            <div className="row">
              <Button variant="secondary" size="sm" onClick={() => open('qr', link)}>
                <QrCode size={16} aria-hidden="true" />
                {t('act.qr')}
              </Button>
              <LinkMenu link={link} open={open} onChanged={(updated) => onChanged(updated)} hideStats />
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
        <section className="card card-pad" aria-labelledby="details-title">
          <h2 id="details-title" style={{ marginBottom: 16 }}>{t('detail.info.title')}</h2>
          <dl className="details-list">
            <dt>{t('detail.info.destination')}</dt>
            <dd>{link.originalUrl}</dd>
            <dt>{t('detail.info.created')}</dt>
            <dd>{time.format(link.createdAt)}</dd>
            <dt>{t('detail.info.updated')}</dt>
            <dd>{time.format(link.updatedAt)}</dd>
            <dt>{t('detail.info.expires')}</dt>
            <dd>{link.expiresAt ? time.format(link.expiresAt) : t('link.neverExpires')}</dd>
            <dt>{t('detail.info.password')}</dt>
            <dd>{link.passwordProtected ? t('detail.info.on') : t('detail.info.off')}</dd>
          </dl>
        </section>
      )}
      {modals}
    </div>
  );
}
