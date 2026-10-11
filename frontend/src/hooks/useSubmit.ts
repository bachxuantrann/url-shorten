import { useCallback, useState } from 'react';
import { toErrorView, type ErrorView } from '../api/errors';
import { useI18n } from '../i18n/I18nProvider';

/**
 * Chạy một thao tác gửi lên server: khoá nút trong lúc chờ, giữ lỗi (kể cả lỗi theo field) để hiện trong form.
 * `run` trả true khi thành công để nơi gọi đóng hộp thoại / làm mới.
 */
export function useSubmit() {
  const { t } = useI18n();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ErrorView | null>(null);

  const run = useCallback(
    async (action: () => Promise<unknown>): Promise<boolean> => {
      setBusy(true);
      setError(null);
      try {
        await action();
        return true;
      } catch (e) {
        setError(toErrorView(e, t));
        return false;
      } finally {
        setBusy(false);
      }
    },
    [t],
  );

  return { busy, error, run };
}
