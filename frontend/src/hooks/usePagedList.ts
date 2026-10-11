import { useCallback, useEffect, useRef, useState } from 'react';
import { toErrorView } from '../api/errors';
import type { PageResponse } from '../api/types';
import { useI18n } from '../i18n/I18nProvider';

/**
 * Tải một trang danh sách có phân trang cho các trang quản trị. `load` phải được bọc useCallback với đúng các bộ lọc
 * và số trang: mỗi lần nó đổi, trang được tải lại và kết quả của request cũ (đã bị bỏ) không ghi đè kết quả mới.
 * Khi trang hiện tại bỗng rỗng (xoá phần tử cuối của trang cuối) gọi `onEmptyPage` để lùi về trang trước.
 */
export function usePagedList<T>(load: () => Promise<PageResponse<T>>, onEmptyPage: () => void) {
  const { t } = useI18n();
  const [data, setData] = useState<PageResponse<T> | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [reloadKey, setReloadKey] = useState(0);
  const emptyPage = useRef(onEmptyPage);
  emptyPage.current = onEmptyPage;

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    load()
      .then((res) => {
        if (cancelled) return;
        if (res.content.length === 0 && res.page > 0) emptyPage.current();
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
  }, [load, reloadKey, t]);

  const reload = useCallback(() => setReloadKey((k) => k + 1), []);
  return { data, setData, loading, error, reload };
}
