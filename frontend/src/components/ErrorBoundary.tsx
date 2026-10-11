import { Component, type ErrorInfo, type ReactNode } from 'react';
import { TriangleAlert } from 'lucide-react';
import { useI18n } from '../i18n/I18nProvider';
import { isChunkError } from '../utils/lazyWithReload';
import { Button, buttonClass } from './ui/Button';

interface Props {
  children: ReactNode;
}

interface State {
  failed: boolean;
  /** Lỗi tải chunk: Thử lại vô dụng (React.lazy nhớ kết quả thất bại), chỉ tải lại trang mới cứu được. */
  chunk: boolean;
}

/**
 * Chặn lỗi lúc render (kể cả tải chunk lazy thất bại sau khi deploy bản mới, khi file băm tên cũ không còn) để người
 * dùng thấy trang báo lỗi có lối thoát thay vì trang trắng. Lỗi không được nuốt: vẫn ghi ra console cho việc điều tra.
 * Dùng thẻ <a href> (tải lại hẳn trang) cho nút về trang chủ vì router có thể đang ở trạng thái hỏng.
 */
export class ErrorBoundary extends Component<Props, State> {
  state: State = { failed: false, chunk: false };

  static getDerivedStateFromError(error: unknown): State {
    return { failed: true, chunk: isChunkError(error) };
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error('Unhandled UI error', error, info.componentStack);
  }

  render() {
    if (!this.state.failed) return this.props.children;
    return <ErrorFallback canRetry={!this.state.chunk} onRetry={() => this.setState({ failed: false, chunk: false })} />;
  }
}

function ErrorFallback({ onRetry, canRetry }: { onRetry: () => void; canRetry: boolean }) {
  const { t } = useI18n();
  return (
    <div className="status-page" role="alert">
      <div className="card status-card">
        <div className="status-icon danger">
          <TriangleAlert size={28} aria-hidden="true" />
        </div>
        <h1>{t('error.boundary.title')}</h1>
        <p className="desc">{t('error.boundary.text')}</p>
        <div className="status-actions">
          <Button size="lg" block onClick={() => window.location.reload()}>
            {t('error.boundary.reload')}
          </Button>
          {canRetry && (
            <Button variant="ghost" block onClick={onRetry}>
              {t('error.boundary.retry')}
            </Button>
          )}
          <a href="/" className={buttonClass({ variant: 'ghost', block: true })}>
            {t('unavailable.home')}
          </a>
        </div>
      </div>
    </div>
  );
}
