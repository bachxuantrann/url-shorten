import { useCallback, useEffect, useState } from 'react';
import { ChevronDown, Link2, ShieldBan } from 'lucide-react';
import { dismissReports, listReports, reportDetails, resolveReports } from '../api/admin';
import { toErrorView } from '../api/errors';
import type { AbuseDetail, AbuseReason, AbuseSummary, ReportStatus } from '../api/types';
import { AdminHead, ListSkeleton, LoadError, Pager } from '../components/admin';
import { BlockModal } from '../components/AdminLinkModals';
import { StatusBadge } from '../components/LinkActions';
import { Button } from '../components/ui/Button';
import { CopyButton } from '../components/ui/CopyButton';
import { EmptyState } from '../components/ui/EmptyState';
import { useToast } from '../components/ui/Toast';
import { useDocumentTitle } from '../hooks/useDocumentTitle';
import { usePagedList } from '../hooks/usePagedList';
import { useServerTime } from '../hooks/useServerTime';
import { useI18n } from '../i18n/I18nProvider';
import type { MessageKey } from '../i18n/messages';
import { displayUrl } from '../utils/datetime';

const PAGE_SIZE = 10;
const STATUSES: ReportStatus[] = ['OPEN', 'RESOLVED', 'DISMISSED'];

/**
 * Quản trị: báo cáo lạm dụng gom theo link. Với mỗi link admin xem từng báo cáo, rồi chặn link (tự đóng báo cáo là đã
 * xử lý), đánh dấu đã xử lý hoặc bỏ qua (không phải lạm dụng).
 */
export function AdminReportsPage() {
  const { t, language } = useI18n();
  useDocumentTitle(t('admin.reports.title'));
  const toast = useToast();
  const { ready: zoneReady } = useServerTime();

  const [status, setStatus] = useState<ReportStatus>('OPEN');
  const [page, setPage] = useState(0);
  const [blocking, setBlocking] = useState<AbuseSummary | null>(null);

  const load = useCallback(() => listReports({ status, page, size: PAGE_SIZE }), [status, page]);
  const { data, loading, error, reload } = usePagedList(load, () => setPage((p) => Math.max(p - 1, 0)));
  const content = data?.content ?? [];

  async function close(item: AbuseSummary, kind: 'resolve' | 'dismiss') {
    try {
      await (kind === 'resolve' ? resolveReports(item.shortCode) : dismissReports(item.shortCode));
      toast.success(t(kind === 'resolve' ? 'toast.reportsResolved' : 'toast.reportsDismissed'));
      reload();
    } catch (e) {
      toast.error(toErrorView(e, t).message);
    }
  }

  return (
    <div className="stack">
      <AdminHead
        title={t('admin.reports.title')}
        sub={data ? t('admin.reports.count', { count: data.totalElements.toLocaleString(language) }) : undefined}
      />

      <section className="card" aria-label={t('admin.reports.title')}>
        <div className="toolbar">
          <div className="segmented" role="group" aria-label={t('links.col.status')}>
            {STATUSES.map((s) => (
              <button
                key={s}
                type="button"
                aria-pressed={status === s}
                onClick={() => {
                  setStatus(s);
                  setPage(0);
                }}
              >
                {t(`admin.reports.status.${s}` as MessageKey)}
              </button>
            ))}
          </div>
        </div>

        {error && <LoadError message={error} onRetry={reload} />}

        {(loading && !data) || !zoneReady ? (
          <ListSkeleton />
        ) : data && content.length === 0 ? (
          <EmptyState
            title={t(`admin.reports.empty.${status}.title` as MessageKey)}
            text={t(`admin.reports.empty.${status}.text` as MessageKey)}
          />
        ) : (
          data && (
            <div aria-busy={loading}>
              {content.map((item) => (
                <ReportCard
                  key={item.shortCode}
                  item={item}
                  status={status}
                  onBlock={() => setBlocking(item)}
                  onClose={(kind) => close(item, kind)}
                />
              ))}
              <Pager data={data} count={content.length} onPage={setPage} />
            </div>
          )
        )}
      </section>

      {/* Chặn tự đóng báo cáo của link đó là đã xử lý: tải lại để nó rời khỏi tab "Chờ xử lý" */}
      <BlockModal link={blocking} onClose={() => setBlocking(null)} onChanged={reload} />
    </div>
  );
}

interface CardProps {
  item: AbuseSummary;
  status: ReportStatus;
  onBlock: () => void;
  onClose: (kind: 'resolve' | 'dismiss') => void;
}

function ReportCard({ item, status, onBlock, onClose }: CardProps) {
  const { t } = useI18n();
  const time = useServerTime();
  const [open, setOpen] = useState(false);
  const alreadyBlocked = item.linkStatus === 'BLOCKED';

  return (
    <div className="link-card">
      <div className="link-cell">
        <span className="link-avatar" aria-hidden="true">
          <Link2 size={18} />
        </span>
        <div className="link-main">
          <div className="link-short">
            <span className="truncate">{displayUrl(item.shortUrl)}</span>
            <CopyButton text={item.shortUrl} />
          </div>
          <div className="link-target truncate" title={item.originalUrl}>
            {item.originalUrl}
          </div>
        </div>
      </div>

      <div className="meta">
        <StatusBadge status={item.linkStatus} />
        <span>
          <strong>{t('admin.reports.reports', { count: item.reportCount })}</strong>
        </span>
        {(Object.entries(item.reasons) as [AbuseReason, number][]).map(([reason, count]) => (
          <span key={reason} className="badge badge-warning">
            {t(`report.reason.${reason}` as MessageKey)} ×{count}
          </span>
        ))}
        <span>{t('admin.reports.last', { date: time.format(item.lastReportedAt) })}</span>
      </div>

      <div className="actions">
        <Button variant="ghost" size="sm" aria-expanded={open} onClick={() => setOpen((o) => !o)}>
          <ChevronDown size={16} aria-hidden="true" style={{ transform: open ? 'rotate(180deg)' : undefined }} />
          {open ? t('admin.reports.hide') : t('admin.reports.view')}
        </Button>
        {status === 'OPEN' && (
          <>
            {!alreadyBlocked && (
              <Button variant="danger" size="sm" onClick={onBlock} aria-label={`${t('admin.reports.block')} ${item.shortCode}`}>
                <ShieldBan size={16} aria-hidden="true" />
                {t('admin.reports.block')}
              </Button>
            )}
            <Button variant="secondary" size="sm" onClick={() => onClose('resolve')} aria-label={`${t('admin.reports.resolve')} ${item.shortCode}`}>
              {t('admin.reports.resolve')}
            </Button>
            <Button variant="secondary" size="sm" onClick={() => onClose('dismiss')} aria-label={`${t('admin.reports.dismiss')} ${item.shortCode}`}>
              {t('admin.reports.dismiss')}
            </Button>
          </>
        )}
      </div>

      {open && <ReportDetails code={item.shortCode} status={status} />}
    </div>
  );
}

/** Các báo cáo cụ thể của một link, tải khi admin bung thẻ ra. */
function ReportDetails({ code, status }: { code: string; status: ReportStatus }) {
  const { t } = useI18n();
  const time = useServerTime();
  const [items, setItems] = useState<AbuseDetail[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    reportDetails(code, status)
      .then((res) => !cancelled && setItems(res))
      .catch((e) => !cancelled && setError(toErrorView(e, t).message));
    return () => {
      cancelled = true;
    };
  }, [code, status, t]);

  if (error) {
    return (
      <div className="alert" role="alert" style={{ marginTop: 12 }}>
        <span>{error}</span>
      </div>
    );
  }
  if (!items) return <div className="skeleton" style={{ height: 48, marginTop: 12 }} aria-label={t('common.loading')} />;

  return (
    <ul className="report-list">
      {items.map((r) => (
        <li key={r.id}>
          <strong>{t(`report.reason.${r.reason}` as MessageKey)}</strong>
          <span className="when"> · {time.format(r.createdAt)}</span>
          <div>{r.details ? r.details : <span className="muted">{t('admin.reports.noDetails')}</span>}</div>
        </li>
      ))}
    </ul>
  );
}
