import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { AlertCircle, Plus } from 'lucide-react';
import { ApiError } from '../api/client';
import { toErrorView } from '../api/errors';
import { getOverview } from '../api/stats';
import type { LinkCounts, StatsOverview, TopLink } from '../api/types';
import { useCreateLink } from '../components/AppLayout';
import { DateRangeBar, KpiSkeleton, StatsBody, useStatsRange } from '../components/StatsView';
import { Button, buttonClass } from '../components/ui/Button';
import { CopyButton } from '../components/ui/CopyButton';
import { EmptyState } from '../components/ui/EmptyState';
import { useDocumentTitle } from '../hooks/useDocumentTitle';
import { useI18n } from '../i18n/I18nProvider';
import type { MessageKey } from '../i18n/messages';
import { displayUrl } from '../utils/datetime';

/** Số link theo trạng thái; màu theo ý nghĩa như huy hiệu trạng thái ở danh sách link. */
const COUNT_KEYS: { key: Exclude<keyof LinkCounts, 'total'>; tone: string; label: MessageKey }[] = [
  { key: 'active', tone: 'success', label: 'link.status.ACTIVE' },
  { key: 'disabled', tone: 'neutral', label: 'link.status.DISABLED' },
  { key: 'expired', tone: 'warning', label: 'link.status.EXPIRED' },
  { key: 'blocked', tone: 'danger', label: 'link.status.BLOCKED' },
];

/**
 * Tổng quan tài khoản: số link theo trạng thái, click theo ngày, link nhiều click nhất và phân bổ của MỌI link của người
 * dùng trong một khoảng ngày (cùng quy tắc ngày của máy chủ như trang thống kê một link).
 */
export function OverviewPage() {
  const { t, language } = useI18n();
  useDocumentTitle(t('nav.overview'));
  const { openCreate, createdVersion } = useCreateLink();
  const { time, range, setRange } = useStatsRange();
  const [data, setData] = useState<StatsOverview | null>(null);
  // retry=false cho lỗi do chính khoảng ngày đã chọn (4xx): thử lại y nguyên vẫn lỗi, người dùng cần sửa khoảng ngày
  const [error, setError] = useState<{ message: string; retry: boolean } | null>(null);
  const [loading, setLoading] = useState(true);
  const [reloadKey, setReloadKey] = useState(0);

  useEffect(() => {
    // Ô ngày đang trống (người dùng đang sửa) hoặc chưa biết múi giờ máy chủ: chưa gọi API
    if (!range || !range.from || !range.to) return;
    let cancelled = false; // bỏ kết quả của request cũ nếu khoảng ngày đã đổi trong lúc chờ
    setLoading(true);
    getOverview(range.from, range.to)
      .then((res) => {
        if (cancelled) return;
        setData(res);
        setError(null);
      })
      .catch((e) => {
        if (cancelled) return;
        // Khoảng ngày sai (ví dụ dài quá 366 ngày): giữ số liệu cũ trên màn hình và báo lỗi
        setError({ message: toErrorView(e, t).message, retry: !(e instanceof ApiError && e.status >= 400 && e.status < 500 && e.status !== 429) });
      })
      .finally(() => !cancelled && setLoading(false));
    return () => {
      cancelled = true;
    };
  }, [range, t, reloadKey, createdVersion]);

  const noLinks = data !== null && data.links.total === 0;

  return (
    <div className="stack">
      <div className="page-head">
        <div>
          <h1>{t('nav.overview')}</h1>
          <p>{t('overview.sub')}</p>
        </div>
      </div>

      {!noLinks && <DateRangeBar range={range} onChange={setRange} lastDays={time.lastDays} />}

      {error && (
        <div className="alert" role="alert">
          <AlertCircle size={18} aria-hidden="true" />
          <span>{error.message}</span>
          {error.retry && (
            <Button variant="secondary" size="sm" style={{ marginLeft: 'auto' }} onClick={() => setReloadKey((k) => k + 1)}>
              {t('common.retry')}
            </Button>
          )}
        </div>
      )}

      {loading && !data && <KpiSkeleton />}

      {noLinks && (
        <div className="card">
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
        </div>
      )}

      {data && !noLinks && (
        // Có lỗi mà vẫn còn số liệu của lần trước: làm mờ để không bị nhầm là số liệu của khoảng ngày đang chọn
        <div className={error ? 'stack stale' : 'stack'} aria-busy={loading}>
          <LinkCountsCard counts={data.links} />
          <StatsBody stats={data} loading={loading} afterChart={<TopLinksCard links={data.topLinks} language={language} />} />
        </div>
      )}
    </div>
  );
}

function LinkCountsCard({ counts }: { counts: LinkCounts }) {
  const { t, language } = useI18n();
  return (
    <section className="card card-pad" aria-label={t('overview.links.title')}>
      <div className="card-head">
        <h2>{t('overview.links.title')}</h2>
        <Link to="/dashboard" className={buttonClass({ variant: 'secondary', size: 'sm' })}>
          {t('overview.manage')}
        </Link>
      </div>
      <dl className="count-chips">
        <div className="count-chip">
          <dt>{t('overview.links.total')}</dt>
          <dd>{counts.total.toLocaleString(language)}</dd>
        </div>
        {COUNT_KEYS.map(({ key, tone, label }) => (
          <div key={key} className={`count-chip ${tone}`}>
            <dt>{t(label)}</dt>
            <dd>{counts[key].toLocaleString(language)}</dd>
          </div>
        ))}
      </dl>
    </section>
  );
}

/** Các link nhiều click nhất trong khoảng đã chọn, bấm vào để xem thống kê của link đó. */
function TopLinksCard({ links, language }: { links: TopLink[]; language: string }) {
  const { t } = useI18n();
  return (
    <section className="card card-pad" aria-label={t('overview.top.title')}>
      <h2 style={{ marginBottom: 16 }}>{t('overview.top.title')}</h2>
      {links.length === 0 ? (
        <p className="muted">{t('overview.top.empty')}</p>
      ) : (
        <ol className="top-links">
          {links.map((link, i) => (
            <li key={link.shortCode}>
              <span className="top-rank" aria-hidden="true">
                {i + 1}
              </span>
              <div className="top-main">
                <div className="link-short">
                  <Link to={`/links/${encodeURIComponent(link.shortCode)}`} className="truncate">
                    {displayUrl(link.shortUrl)}
                  </Link>
                  <CopyButton text={link.shortUrl} />
                </div>
                <div className="link-target truncate" title={link.originalUrl}>
                  {link.originalUrl}
                </div>
              </div>
              <span className="top-clicks">{t('link.clicks', { count: link.clicks.toLocaleString(language) })}</span>
            </li>
          ))}
        </ol>
      )}
    </section>
  );
}
