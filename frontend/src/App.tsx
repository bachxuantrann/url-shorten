import { Suspense, type ReactElement } from 'react';
import { Navigate, Route, Routes, useLocation } from 'react-router-dom';
import { isAdmin, useAuth } from './auth/AuthProvider';
import { AppLayout } from './components/AppLayout';
import { PublicLayout } from './components/PublicLayout';
import { useI18n } from './i18n/I18nProvider';
import { AdminDomainsPage } from './pages/AdminDomainsPage';
import { AdminLinksPage } from './pages/AdminLinksPage';
import { AdminReportsPage } from './pages/AdminReportsPage';
import { AdminUsersPage } from './pages/AdminUsersPage';
import { HomePage } from './pages/HomePage';
import { LinksPage } from './pages/LinksPage';
import { LoginPage } from './pages/LoginPage';
import { RegisterPage } from './pages/RegisterPage';
import { ReportPage } from './pages/ReportPage';
import { SettingsPage } from './pages/SettingsPage';
import { OverviewPage } from './pages/OverviewPage';
import { NotFoundPage, UnavailablePage, UnlockPage } from './pages/StatusPages';
import { lazyWithReload } from './utils/lazyWithReload';

// Trang chi tiết kéo theo thư viện biểu đồ (nặng): chỉ tải khi người dùng mở nó
// (lazyWithReload tự tải lại trang một lần nếu chunk không còn sau khi deploy bản mới)
const LinkDetailPage = lazyWithReload(() => import('./pages/LinkDetailPage').then((m) => ({ default: m.LinkDetailPage })));

/** Trang cần đăng nhập: chưa đăng nhập thì sang /login và quay lại đúng trang sau khi vào. */
function RequireAuth({ children }: { children: ReactElement }) {
  const { user, loading } = useAuth();
  const { t } = useI18n();
  const location = useLocation();
  if (loading) return <p className="muted" style={{ padding: 24 }}>{t('common.loading')}</p>;
  if (!user) return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  return children;
}

/** Trang chỉ dành cho admin: người dùng thường quay về danh sách link của mình (API admin vẫn trả 403 nếu bị gọi trực tiếp). */
function RequireAdmin({ children }: { children: ReactElement }) {
  const { user } = useAuth();
  if (!isAdmin(user)) return <Navigate to="/dashboard" replace />;
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
        <Route path="report" element={<ReportPage />} />
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
        <Route path="overview" element={<OverviewPage />} />
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

        {/* Khu quản trị: /admin* nằm trong ShortCodes.RESERVED và @ui của Caddyfile */}
        <Route path="admin">
          <Route index element={<Navigate to="links" replace />} />
          <Route path="links" element={<RequireAdmin><AdminLinksPage /></RequireAdmin>} />
          <Route path="domains" element={<RequireAdmin><AdminDomainsPage /></RequireAdmin>} />
          <Route path="reports" element={<RequireAdmin><AdminReportsPage /></RequireAdmin>} />
          <Route path="users" element={<RequireAdmin><AdminUsersPage /></RequireAdmin>} />
        </Route>
      </Route>
    </Routes>
  );
}
