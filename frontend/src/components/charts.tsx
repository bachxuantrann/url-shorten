import { Bar, CartesianGrid, ComposedChart, Legend, Line, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import type { BreakdownItem, DailyStat } from '../api/types';
import { useI18n } from '../i18n/I18nProvider';
import type { MessageKey } from '../i18n/messages';

// Màu lấy từ biến CSS (--chart-*) nên tự đổi theo chế độ sáng/tối
const HUMAN = 'var(--chart-1)';
const BOT = 'var(--chart-3)';
const UNIQUE = 'var(--chart-2)';

/** Click theo từng ngày: cột chồng người + bot, đường khách duy nhất. */
export function DailyChart({ data }: { data: DailyStat[] }) {
  const { t } = useI18n();
  const rows = data.map((d) => ({
    // "2026-10-11" -> "10-11" cho trục gọn; ngày đầy đủ nằm trong tooltip
    label: d.date.slice(5),
    date: d.date,
    human: d.clicks - d.botClicks,
    bots: d.botClicks,
    unique: d.uniqueVisitors,
  }));

  return (
    <div className="chart" role="img" aria-label={t('chart.daily')}>
      <ResponsiveContainer width="100%" height={300}>
        <ComposedChart data={rows} margin={{ top: 8, right: 8, bottom: 0, left: -16 }}>
          <CartesianGrid strokeDasharray="3 3" stroke="var(--border)" vertical={false} />
          <XAxis dataKey="label" tick={{ fill: 'var(--muted)', fontSize: 12 }} minTickGap={16} tickLine={false} axisLine={{ stroke: 'var(--border)' }} />
          <YAxis allowDecimals={false} tick={{ fill: 'var(--muted)', fontSize: 12 }} tickLine={false} axisLine={false} />
          <Tooltip
            cursor={{ fill: 'var(--surface-2)' }}
            labelFormatter={(_, payload) => payload?.[0]?.payload?.date ?? ''}
            contentStyle={{ background: 'var(--surface)', border: '1px solid var(--border)', borderRadius: 10, color: 'var(--text)' }}
          />
          <Legend iconType="circle" />
          <Bar dataKey="human" name={t('chart.human')} stackId="clicks" fill={HUMAN} radius={[0, 0, 0, 0]} />
          <Bar dataKey="bots" name={t('chart.bots')} stackId="clicks" fill={BOT} radius={[4, 4, 0, 0]} />
          <Line dataKey="unique" name={t('chart.unique')} type="monotone" stroke={UNIQUE} strokeWidth={2} dot={false} />
        </ComposedChart>
      </ResponsiveContainer>
    </div>
  );
}

type Kind = 'country' | 'device' | 'plain';

/** Tên hiển thị cho một giá trị phân bổ: quốc gia dịch theo ngôn ngữ giao diện, thiết bị dùng bản dịch, "Unknown" thành "Không rõ". */
function labelFor(name: string, kind: Kind, language: string, t: (k: MessageKey) => string): string {
  if (name === 'Unknown') return t('chart.unknown');
  if (kind === 'country') {
    try {
      return new Intl.DisplayNames([language], { type: 'region' }).of(name) ?? name;
    } catch {
      return name;
    }
  }
  if (kind === 'device') {
    const key = `device.${name}` as MessageKey;
    const text = t(key);
    return text === key ? name : text;
  }
  return name;
}

/** Danh sách xếp hạng kèm thanh tỉ lệ: dễ đọc hơn biểu đồ cột cho các nhóm "top N" (quốc gia, trình duyệt...). */
export function RankList({ title, items, kind = 'plain' }: { title: string; items: BreakdownItem[]; kind?: Kind }) {
  const { t, language } = useI18n();
  const total = items.reduce((sum, i) => sum + i.clicks, 0);

  return (
    <section className="card card-pad">
      <h3 style={{ marginBottom: 14 }}>{title}</h3>
      {items.length === 0 ? (
        <p className="muted small">{t('chart.noItems')}</p>
      ) : (
        <ul className="rank">
          {items.map((item) => {
            const pct = total > 0 ? Math.round((item.clicks / total) * 100) : 0;
            return (
              <li key={item.name}>
                <span className="truncate">{labelFor(item.name, kind, language, t)}</span>
                <span className="pct">
                  <strong style={{ color: 'var(--text)' }}>{item.clicks.toLocaleString(language)}</strong> · {pct}%
                </span>
                <span className="bar" role="presentation">
                  <span style={{ width: `${pct}%` }} />
                </span>
              </li>
            );
          })}
        </ul>
      )}
    </section>
  );
}
