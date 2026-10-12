import { useCallback, useEffect, useState } from 'react';
import { ChevronDown, ExternalLink, RefreshCw } from 'lucide-react';
import { monitoringLogs, monitoringStatus, monitoringSummary } from '../api/admin';
import { toErrorView } from '../api/errors';
import type { LogEntry, LogLevel, LogSummary, MonitoringStatus } from '../api/types';
import { AdminHead, ListSkeleton, LoadError, Pager, SearchBox } from '../components/admin';
import { Button } from '../components/ui/Button';
import { CopyButton } from '../components/ui/CopyButton';
import { EmptyState } from '../components/ui/EmptyState';
import { useDocumentTitle } from '../hooks/useDocumentTitle';
import { usePagedList } from '../hooks/usePagedList';
import { useServerTime } from '../hooks/useServerTime';
import { useI18n } from '../i18n/I18nProvider';
import type { MessageKey } from '../i18n/messages';
import { useDebounced } from '../utils/useDebounced';

const PAGE_SIZE = 25;
const RANGES = [15, 60, 360, 1440] as const;
const LEVELS: LogLevel[] = ['ERROR', 'WARN', 'INFO', 'DEBUG'];
const REFRESH_MS = 10_000;

const TONE: Record<string, string> = { ERROR: 'badge-danger', WARN: 'badge-warning', INFO: 'badge-primary', DEBUG: '', TRACE: '' };

/**
 * Quản trị: xem log của hệ thống lấy từ OpenObserve. Trình duyệt chỉ nói chuyện với backend của mình; backend giữ thông tin
 * đăng nhập OpenObserve và là bên gọi. Có đếm theo mức (bấm để lọc), top exception, tìm trong nội dung, tự làm mới và nút mở
 * giao diện OpenObserve để xem trace và metric.
 */
export function AdminMonitoringPage() {
  const { t } = useI18n();
  useDocumentTitle(t('monitor.title'));

  const [status, setStatus] = useState<MonitoringStatus | null>(null);
  const [statusError, setStatusError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    monitoringStatus()
      .then((s) => !cancelled && setStatus(s))
      .catch((e) => !cancelled && setStatusError(toErrorView(e, t).message));
    return () => {
      cancelled = true;
    };
  }, [t]);

  if (statusError) {
    return (
      <div className="stack">
        <AdminHead title={t('monitor.title')} />
        <section className="card">
          <LoadError message={statusError} onRetry={() => window.location.reload()} />
        </section>
      </div>
    );
  }
  if (!status) {
    return (
      <div className="stack">
        <AdminHead title={t('monitor.title')} sub={t('monitor.sub')} />
        <section className="card">
          <ListSkeleton />
        </section>
      </div>
    );
  }
  // Chỉ dựng phần xem log khi đã cấu hình: chưa cấu hình thì không gọi summary / logs lần nào
  if (!status.configured) {
    return (
      <div className="stack">
        <AdminHead title={t('monitor.title')} sub={t('monitor.sub')} actions={<StatusActions status={status} />} />
        <section className="card">
          <EmptyState title={t('monitor.notConfigured.title')} text={t('monitor.notConfigured.text')} />
        </section>
      </div>
    );
  }
  return <MonitoringView status={status} />;
}

/** Huy hiệu kết nối và nút mở giao diện OpenObserve. */
function StatusActions({ status }: { status: MonitoringStatus }) {
  const { t } = useI18n();
  return (
    <div className="row" style={{ gap: 8 }}>
      {status.configured && (
        <span className={`badge ${status.reachable ? 'badge-success' : 'badge-danger'}`}>
          {status.reachable ? t('monitor.status.ok') : t('monitor.status.down')}
        </span>
      )}
      {status.uiUrl && (
        <a className="btn btn-secondary btn-sm" href={status.uiUrl} target="_blank" rel="noopener noreferrer">
          <ExternalLink size={16} aria-hidden="true" />
          {t('monitor.open')}
        </a>
      )}
    </div>
  );
}

function MonitoringView({ status }: { status: MonitoringStatus }) {
  const { t, language } = useI18n();
  const { ready: zoneReady } = useServerTime();

  const [minutes, setMinutes] = useState<number>(60);
  const [levels, setLevels] = useState<LogLevel[]>(['ERROR', 'WARN']);
  const [keyword, setKeyword] = useState('');
  const [page, setPage] = useState(0);
  const [auto, setAuto] = useState(false);
  const [summary, setSummary] = useState<LogSummary | null>(null);
  const [summaryError, setSummaryError] = useState<string | null>(null);
  const [tick, setTick] = useState(0);

  useEffect(() => {
    let cancelled = false;
    monitoringSummary(minutes)
      .then((s) => {
        if (cancelled) return;
        setSummary(s);
        setSummaryError(null);
      })
      .catch((e) => !cancelled && setSummaryError(toErrorView(e, t).message));
    return () => {
      cancelled = true;
    };
  }, [minutes, tick, t]);

  const debouncedKeyword = useDebounced(keyword.trim());
  const load = useCallback(
    () => monitoringLogs({ levels: levels.join(','), keyword: debouncedKeyword || undefined, minutes, page, size: PAGE_SIZE }),
    [levels, debouncedKeyword, minutes, page],
  );
  const logs = usePagedList(load, () => setPage((p) => Math.max(p - 1, 0)));

  const refresh = useCallback(() => {
    setTick((n) => n + 1);
    logs.reload();
  }, [logs]);

  // Tự làm mới: chỉ khi bật; dọn bộ hẹn giờ khi tắt hoặc rời trang
  const reloadLogs = logs.reload;
  useEffect(() => {
    if (!auto) return;
    const id = setInterval(() => {
      setTick((n) => n + 1);
      reloadLogs();
    }, REFRESH_MS);
    return () => clearInterval(id);
  }, [auto, reloadLogs]);

  function toggleLevel(level: LogLevel) {
    setLevels((current) => {
      const next = current.includes(level) ? current.filter((l) => l !== level) : [...current, level];
      return next.length === 0 ? current : LEVELS.filter((l) => next.includes(l)); // luôn còn ít nhất một mức
    });
    setPage(0);
  }

  const content = logs.data?.content ?? [];

  return (
    <div className="stack">
      <AdminHead
        title={t('monitor.title')}
        sub={logs.data ? t('monitor.count', { count: logs.data.totalElements.toLocaleString(language) }) : t('monitor.sub')}
        actions={<StatusActions status={status} />}
      />

      <section className="card card-pad" aria-label={t('monitor.summary')}>
        <div className="toolbar" style={{ padding: 0, marginBottom: 14 }}>
          <div className="segmented" role="group" aria-label={t('monitor.range')}>
            {RANGES.map((m) => (
              <button
                key={m}
                type="button"
                aria-pressed={minutes === m}
                onClick={() => {
                  setMinutes(m);
                  setPage(0);
                }}
              >
                {t(`monitor.range.${m}` as MessageKey)}
              </button>
            ))}
          </div>
          <label className="row" style={{ gap: 8, fontSize: '0.9rem' }}>
            <input type="checkbox" checked={auto} onChange={(e) => setAuto(e.target.checked)} />
            {t('monitor.auto')}
          </label>
          <Button variant="secondary" size="sm" onClick={refresh}>
            <RefreshCw size={16} aria-hidden="true" />
            {t('monitor.refresh')}
          </Button>
        </div>

        {summaryError && (
          <div className="alert" role="alert">
            <span>{summaryError}</span>
          </div>
        )}
        <div className="level-cards" role="group" aria-label={t('monitor.levels')}>
          {LEVELS.map((level) => (
            <button key={level} type="button" className={`level-card ${TONE[level]}`} aria-pressed={levels.includes(level)} onClick={() => toggleLevel(level)}>
              <span className="level-count">{summary ? summary.levels[level].toLocaleString(language) : '–'}</span>
              <span className="level-name">{t(`monitor.level.${level}` as MessageKey)}</span>
            </button>
          ))}
        </div>

        {summary && (
          <div style={{ marginTop: 16 }}>
            <h2 className="card-head">{t('monitor.exceptions')}</h2>
            {summary.topExceptions.length === 0 ? (
              <p className="muted small">{t('monitor.exceptions.none')}</p>
            ) : (
              <ul className="report-list">
                {summary.topExceptions.map((e) => (
                  <li key={e.type}>
                    <strong className="break">{e.type}</strong> <span className="when">× {e.count.toLocaleString(language)}</span>
                  </li>
                ))}
              </ul>
            )}
          </div>
        )}
      </section>

      <section className="card" aria-label={t('monitor.logs')}>
        <div className="toolbar">
          <SearchBox
            label={t('monitor.search')}
            value={keyword}
            onChange={(v) => {
              setKeyword(v);
              setPage(0);
            }}
          />
        </div>

        {logs.error && <LoadError message={logs.error} onRetry={logs.reload} />}

        {(logs.loading && !logs.data) || !zoneReady ? (
          <ListSkeleton />
        ) : logs.data && content.length === 0 ? (
          <EmptyState title={t('monitor.empty.title')} text={t('monitor.empty.text')} />
        ) : (
          logs.data && (
            <div aria-busy={logs.loading}>
              {content.map((entry, i) => (
                <LogRow key={`${entry.timestamp}-${logs.data!.page}-${i}`} entry={entry} />
              ))}
              <Pager data={logs.data} count={content.length} onPage={setPage} />
            </div>
          )
        )}
      </section>
    </div>
  );
}

function LogRow({ entry }: { entry: LogEntry }) {
  const { t } = useI18n();
  const time = useServerTime();
  const [open, setOpen] = useState(false);
  const shortLogger = entry.logger?.split('.').slice(-1)[0];
  const hasDetails = Boolean(entry.exceptionType || entry.exceptionStacktrace || entry.traceId || (entry.message && entry.message.length > 160));

  return (
    <div className="log-row">
      <div className="log-head">
        <span className={`badge ${TONE[entry.level ?? ''] ?? ''}`}>{entry.level}</span>
        <span className="muted small">{time.format(entry.timestamp)}</span>
        {shortLogger && (
          <span className="muted small truncate" title={entry.logger}>
            {shortLogger}
          </span>
        )}
        {hasDetails && (
          <Button variant="ghost" size="sm" aria-expanded={open} onClick={() => setOpen((o) => !o)} style={{ marginLeft: 'auto' }}>
            <ChevronDown size={16} aria-hidden="true" style={{ transform: open ? 'rotate(180deg)' : undefined }} />
            {open ? t('monitor.hide') : t('monitor.details')}
          </Button>
        )}
      </div>
      <div className={open ? 'log-message break' : 'log-message log-clamp break'}>{entry.message}</div>
      {open && (
        <div className="log-detail">
          {entry.exceptionType && (
            <p className="break">
              <strong>{entry.exceptionType}</strong>
              {entry.exceptionMessage ? `: ${entry.exceptionMessage}` : ''}
            </p>
          )}
          {entry.exceptionStacktrace && <pre className="log-pre">{entry.exceptionStacktrace}</pre>}
          {entry.traceId && (
            <div className="row" style={{ gap: 6 }}>
              <span className="muted small">{t('monitor.trace')}:</span>
              <code className="small">{entry.traceId}</code>
              <CopyButton text={entry.traceId} />
            </div>
          )}
        </div>
      )}
    </div>
  );
}
