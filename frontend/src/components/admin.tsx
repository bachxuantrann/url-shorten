import type { ReactNode } from 'react';
import { AlertCircle, Search, X } from 'lucide-react';
import type { PageResponse } from '../api/types';
import { useI18n } from '../i18n/I18nProvider';
import { Button } from './ui/Button';

/** Ô tìm kiếm của thanh công cụ quản trị (có nút xoá). */
export function SearchBox({ value, onChange, label }: { value: string; onChange: (value: string) => void; label: string }) {
  const { t } = useI18n();
  return (
    <div className="input-wrap">
      <Search className="input-icon" size={18} aria-hidden="true" />
      <input
        type="search"
        className="input with-icon"
        aria-label={label}
        placeholder={label}
        value={value}
        maxLength={100}
        onChange={(e) => onChange(e.target.value)}
      />
      {value && (
        <button type="button" className="btn btn-ghost btn-icon btn-sm input-trailing" aria-label={t('links.clearSearch')} onClick={() => onChange('')}>
          <X size={16} aria-hidden="true" />
        </button>
      )}
    </div>
  );
}

/** Dòng lỗi tải danh sách kèm nút thử lại. */
export function LoadError({ message, onRetry }: { message: string; onRetry: () => void }) {
  const { t } = useI18n();
  return (
    <div style={{ padding: 16 }}>
      <div className="alert" role="alert">
        <AlertCircle size={18} aria-hidden="true" />
        <span>{message}</span>
        <Button variant="secondary" size="sm" style={{ marginLeft: 'auto' }} onClick={onRetry}>
          {t('common.retry')}
        </Button>
      </div>
    </div>
  );
}

/** Khung chờ khi tải lần đầu. */
export function ListSkeleton() {
  const { t } = useI18n();
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

/** Chân bảng: "Hiển thị a-b trên n" và nút trước/sau; ẩn khi chỉ có một trang. */
export function Pager<T>({ data, count, onPage }: { data: PageResponse<T>; count: number; onPage: (page: number) => void }) {
  const { t } = useI18n();
  if (data.totalPages <= 1) return null;
  const from = data.totalElements > 0 ? data.page * data.size + 1 : 0;
  const to = data.page * data.size + count;
  return (
    <div className="pager">
      <span>{t('links.showing', { from, to, total: data.totalElements })}</span>
      <div className="row" style={{ gap: 8 }}>
        <Button variant="secondary" size="sm" disabled={!data.hasPrevious} onClick={() => onPage(data.page - 1)}>
          {t('links.prev')}
        </Button>
        <Button variant="secondary" size="sm" disabled={!data.hasNext} onClick={() => onPage(data.page + 1)}>
          {t('links.next')}
        </Button>
      </div>
    </div>
  );
}

/** Tiêu đề trang quản trị với dòng phụ (tổng số) và vùng nút hành động bên phải. */
export function AdminHead({ title, sub, actions }: { title: string; sub?: string; actions?: ReactNode }) {
  return (
    <div className="page-head">
      <div>
        <h1>{title}</h1>
        {sub && <p>{sub}</p>}
      </div>
      {actions}
    </div>
  );
}
