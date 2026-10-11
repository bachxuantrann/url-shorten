import { useEffect, useState, type ReactNode } from 'react';
import { Bot, MousePointerClick, UserCheck, Users } from 'lucide-react';
import type { UrlStats } from '../api/types';
import { useServerTime } from '../hooks/useServerTime';
import { useI18n } from '../i18n/I18nProvider';
import { DailyChart, RankList } from './charts';

/** Khoảng ngày đang xem, dạng yyyy-MM-dd; ô trống khi người dùng đang sửa. */
export interface DateRange {
  from: string;
  to: string;
}

const PRESETS = [7, 30, 90];

/**
 * Khoảng ngày của trang thống kê. Mặc định 30 ngày gần nhất tính theo NGÀY CỦA MÁY CHỦ (thống kê gộp theo ngày máy chủ),
 * nên đợi biết múi giờ rồi mới đặt: `range` là null cho tới lúc đó và nơi gọi chưa được gọi API.
 */
export function useStatsRange() {
  const time = useServerTime();
  const [range, setRange] = useState<DateRange | null>(null);
  useEffect(() => {
    if (time.ready && range === null) setRange(time.lastDays(30));
  }, [time, range]);
  return { time, range, setRange };
}

interface RangeBarProps {
  range: DateRange | null;
  onChange: (range: DateRange | ((prev: DateRange | null) => DateRange)) => void;
  lastDays: (days: number) => DateRange;
}

/** Thanh chọn khoảng ngày: 7/30/90 ngày gần nhất hoặc tự chọn hai đầu. */
export function DateRangeBar({ range, onChange, lastDays }: RangeBarProps) {
  const { t } = useI18n();
  const isPreset = (days: number) => {
    const r = lastDays(days);
    return r.from === range?.from && r.to === range?.to;
  };
  return (
    <section className="card card-pad" aria-label={t('detail.range.label')}>
      <div className="date-range">
        <div className="segmented" role="group" aria-label={t('chart.daily')}>
          {PRESETS.map((days) => (
            <button key={days} type="button" aria-pressed={isPreset(days)} onClick={() => onChange(lastDays(days))}>
              {t('detail.range.days', { days })}
            </button>
          ))}
        </div>
        <span className="spacer" />
        <label className="row small muted" style={{ gap: 8 }}>
          {t('detail.range.from')}
          <input
            type="date"
            value={range?.from ?? ''}
            max={range?.to || undefined}
            onChange={(e) => onChange((r) => ({ from: e.target.value, to: r?.to ?? '' }))}
          />
        </label>
        <label className="row small muted" style={{ gap: 8 }}>
          {t('detail.range.to')}
          <input
            type="date"
            value={range?.to ?? ''}
            min={range?.from || undefined}
            onChange={(e) => onChange((r) => ({ from: r?.from ?? '', to: e.target.value }))}
          />
        </label>
      </div>
    </section>
  );
}

/** Ô số liệu lớn (icon + giá trị + nhãn). */
export function Kpi({ icon: Icon, label, value }: { icon: typeof Users; label: string; value: string }) {
  return (
    <div className="card kpi">
      <span className="icon-box">
        <Icon size={20} aria-hidden="true" />
      </span>
      <div>
        <div className="value">{value}</div>
        <div className="label">{label}</div>
      </div>
    </div>
  );
}

/** Khung chờ của hàng KPI khi chưa có dữ liệu lần đầu. */
export function KpiSkeleton() {
  const { t } = useI18n();
  return (
    <div className="kpis" aria-busy="true" aria-label={t('common.loading')}>
      {[0, 1, 2, 3].map((i) => (
        <div key={i} className="card skeleton" style={{ height: 82 }} />
      ))}
    </div>
  );
}

/** Phần số liệu giống nhau của thống kê một link và tổng quan tài khoản. */
export type StatsData = Pick<UrlStats, 'totals' | 'daily' | 'countries' | 'devices' | 'browsers' | 'operatingSystems'>;

/**
 * KPI, biểu đồ theo ngày và các bảng phân bổ (quốc gia, thiết bị, trình duyệt, hệ điều hành). `afterChart` là phần
 * chèn thêm ngay dưới biểu đồ theo ngày (tổng quan tài khoản dùng nó cho bảng xếp hạng link).
 */
export function StatsBody({ stats, loading, afterChart }: { stats: StatsData; loading: boolean; afterChart?: ReactNode }) {
  const { t, language } = useI18n();
  const time = useServerTime();
  const fmt = (n: number) => n.toLocaleString(language);
  return (
    <div className="stack" aria-busy={loading}>
      <div className="kpis">
        <Kpi icon={MousePointerClick} label={t('kpi.total')} value={fmt(stats.totals.clicks)} />
        <Kpi icon={Users} label={t('kpi.human')} value={fmt(stats.totals.clicks - stats.totals.botClicks)} />
        <Kpi icon={Bot} label={t('kpi.bots')} value={fmt(stats.totals.botClicks)} />
        <Kpi icon={UserCheck} label={t('kpi.unique')} value={fmt(stats.totals.uniqueVisitors)} />
      </div>
      <p className="muted small">{t('kpi.uniqueNote')}</p>

      <section className="card card-pad">
        <h2 style={{ marginBottom: 16 }}>{t('chart.daily')}</h2>
        {stats.totals.clicks === 0 ? <p className="muted">{t('chart.noData')}</p> : <DailyChart data={stats.daily} />}
      </section>

      {afterChart}

      <div className="grid-2">
        <RankList title={t('chart.countries')} items={stats.countries} kind="country" />
        <RankList title={t('chart.devices')} items={stats.devices} kind="device" />
        <RankList title={t('chart.browsers')} items={stats.browsers} />
        <RankList title={t('chart.os')} items={stats.operatingSystems} />
      </div>
      <p className="muted small">{t('chart.breakdownNote')}</p>
      {time.differs && <p className="muted small">{t('chart.tzNote', { zone: time.serverZoneLabel })}</p>}
    </div>
  );
}
