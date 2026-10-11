import { lazy, Suspense, type ReactElement } from 'react';
import { Navigate, Route, Routes, useLocation } from 'react-router-dom';
import { useAuth } from './auth/AuthProvider';
import { AppLayout } from './components/AppLayout';
import { PublicLayout } from './components/PublicLayout';
import { useI18n } from './i18n/I18nProvider';
import { HomePage } from './pages/HomePage';
import { LinksPage } from './pages/LinksPage';
import { LoginPage } from './pages/LoginPage';
import { RegisterPage } from './pages/RegisterPage';
import { SettingsPage } from './pages/SettingsPage';
import { NotFoundPage, UnavailablePage, UnlockPage } from './pages/StatusPages';

// Trang chi tiết kéo theo thư viện biểu đồ (nặng): chỉ tải khi người dùng mở nó
const LinkDetailPage = lazy(() => import('./pages/LinkDetailPage').then((m) => ({ default: m.LinkDetailPage })));

/** Trang cần đăng nhập: chưa đăng nhập thì sang /login và quay lại đúng trang sau khi vào. */
function RequireAuth({ children }: { children: ReactElement }) {
  const { user, loading } = useAuth();
  const { t } = useI18n();
  const location = useLocation();
  if (loading) return <p className="muted" style={{ padding: 24 }}>{t('common.loading')}</p>;
  if (!user) return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  return children;
}

// Route mới của UI phải được thêm vào ShortCodes.RESERVED ở backend và vào @ui trong caddy/Caddyfile
// (chung namespace với short code)
export function App() {
  const { t } = useI18n();
  return (
    <Routes>
      <Route element={<PublicLayout />}>
        <Route index element={<HomePage />} />
        <Route path="unlock/:code" element={<UnlockPage />} />
        <Route path="unavailable/:reason" element={<UnavailablePage />} />
        <Route path="*" element={<NotFoundPage />} />
      </Route>

      <Route path="login" element={<LoginPage />} />
      <Route path="register" element={<RegisterPage />} />

      <Route
        element={
          <RequireAuth>
            <AppLayout />
          </RequireAuth>
        }
      >
        <Route path="dashboard" element={<LinksPage />} />
        <Route
          path="links/:code"
          element={
            <Suspense fallback={<p className="muted">{t('common.loading')}</p>}>
              <LinkDetailPage />
            </Suspense>
          }
        />
        <Route path="settings" element={<SettingsPage />} />
      </Route>
    </Routes>
  );
}
