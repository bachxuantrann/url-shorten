import { useCallback, useState } from 'react';
import { Check, Link2, X } from 'lucide-react';
import { listAppeals } from '../api/admin';
import type { AdminAppeal, AppealStatus } from '../api/types';
import { AdminHead, ListSkeleton, LoadError, Pager } from '../components/admin';
import { AcceptAppealModal, RejectAppealModal } from '../components/AdminAppealModals';
import { StatusBadge } from '../components/LinkActions';
import { Button } from '../components/ui/Button';
import { CopyButton } from '../components/ui/CopyButton';
import { EmptyState } from '../components/ui/EmptyState';
import { useDocumentTitle } from '../hooks/useDocumentTitle';
import { usePagedList } from '../hooks/usePagedList';
import { useServerTime } from '../hooks/useServerTime';
import { useI18n } from '../i18n/I18nProvider';
import type { MessageKey } from '../i18n/messages';
import { displayUrl } from '../utils/datetime';

const PAGE_SIZE = 10;
const STATUSES: AppealStatus[] = ['OPEN', 'ACCEPTED', 'REJECTED'];

/**
 * Quản trị: kháng nghị của chủ các link bị chặn. Chấp nhận thì gỡ chặn link (server làm cùng lúc), từ chối thì link
 * vẫn bị chặn và chủ link thấy lời phản hồi (nếu có).
 */
export function AdminAppealsPage() {
  const { t, language } = useI18n();
  useDocumentTitle(t('admin.appeals.title'));
  const { ready: zoneReady } = useServerTime();

  const [status, setStatus] = useState<AppealStatus>('OPEN');
  const [page, setPage] = useState(0);
  const [accepting, setAccepting] = useState<AdminAppeal | null>(null);
  const [rejecting, setRejecting] = useState<AdminAppeal | null>(null);

  const load = useCallback(() => listAppeals({ status, page, size: PAGE_SIZE }), [status, page]);
  const { data, loading, error, reload } = usePagedList(load, () => setPage((p) => Math.max(p - 1, 0)));
  const content = data?.content ?? [];

  return (
    <div className="stack">
      <AdminHead
        title={t('admin.appeals.title')}
        sub={data ? t('admin.appeals.count', { count: data.totalElements.toLocaleString(language) }) : undefined}
      />

      <section className="card" aria-label={t('admin.appeals.title')}>
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
                {t(`admin.appeals.status.${s}` as MessageKey)}
              </button>
            ))}
          </div>
        </div>

        {error && <LoadError message={error} onRetry={reload} />}

        {(loading && !data) || !zoneReady ? (
          <ListSkeleton />
        ) : data && content.length === 0 ? (
          <EmptyState
            title={t(`admin.appeals.empty.${status}.title` as MessageKey)}
            text={t(`admin.appeals.empty.${status}.text` as MessageKey)}
          />
        ) : (
          data && (
            <div aria-busy={loading}>
              {content.map((item) => (
                <AppealCard
                  key={item.id}
                  item={item}
                  onAccept={() => setAccepting(item)}
                  onReject={() => setRejecting(item)}
                />
              ))}
              <Pager data={data} count={content.length} onPage={setPage} />
            </div>
          )
        )}
      </section>

      {/* Sau khi xử lý, tải lại để kháng nghị rời khỏi tab "Chờ xem xét" */}
      <AcceptAppealModal appeal={accepting} onClose={() => setAccepting(null)} onDone={reload} />
      <RejectAppealModal appeal={rejecting} onClose={() => setRejecting(null)} onDone={reload} />
    </div>
  );
}

interface CardProps {
  item: AdminAppeal;
  onAccept: () => void;
  onReject: () => void;
}

function AppealCard({ item, onAccept, onReject }: CardProps) {
  const { t } = useI18n();
  const time = useServerTime();

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
          <strong>{t('admin.appeals.owner', { name: item.owner })}</strong>
        </span>
        <span>{t('admin.appeals.sent', { date: time.format(item.createdAt) })}</span>
        {item.status !== 'OPEN' && <span>{t('admin.appeals.handled', { date: time.format(item.updatedAt) })}</span>}
      </div>

      {item.blockedReason && <p className="muted small break">{t('admin.appeals.reason', { reason: item.blockedReason })}</p>}

      <blockquote className="appeal-message">
        <div className="muted small">{t('admin.appeals.message')}</div>
        <div className="break">{item.message}</div>
      </blockquote>

      {item.response && <p className="muted small break">{t('admin.appeals.response', { response: item.response })}</p>}

      {item.status === 'OPEN' && (
        <div className="actions">
          <Button size="sm" onClick={onAccept} aria-label={`${t('admin.appeals.accept')} ${item.shortCode}`}>
            <Check size={16} aria-hidden="true" />
            {t('admin.appeals.accept')}
          </Button>
          <Button variant="secondary" size="sm" onClick={onReject} aria-label={`${t('admin.appeals.reject')} ${item.shortCode}`}>
            <X size={16} aria-hidden="true" />
            {t('admin.appeals.reject')}
          </Button>
        </div>
      )}
    </div>
  );
}
