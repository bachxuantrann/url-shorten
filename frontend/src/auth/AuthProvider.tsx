import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { request, setAccountLockedHandler, setSessionExpiredHandler, tokenStore } from '../api/client';
import type { LoginResponse, RegisterInput, User } from '../api/types';

interface AuthValue {
  user: User | null;
  /** true cho tới khi biết chắc có phiên đăng nhập hợp lệ hay không (tránh nháy trang login khi F5). */
  loading: boolean;
  login: (username: string, password: string) => Promise<void>;
  register: (input: RegisterInput) => Promise<void>;
  logout: () => Promise<void>;
  /** Thay thông tin người dùng đang hiển thị (sau khi sửa hồ sơ). */
  setUser: (user: User) => void;
  /** true khi server vừa báo tài khoản bị admin khoá: giao diện hiện hộp thoại giải thích (phiên đã bị xoá). */
  accountLocked: boolean;
  dismissAccountLocked: () => void;
}

const AuthContext = createContext<AuthValue | null>(null);

/** Chu kỳ hỏi server "tôi còn đăng nhập được không" khi tab đang mở: người đứng yên cũng biết tài khoản bị khoá trong vài chục giây. */
export const SESSION_CHECK_MS = 30_000;

function sameUser(a: User, b: User): boolean {
  return (
    a.id === b.id && a.username === b.username && a.email === b.email && a.fullName === b.fullName && a.status === b.status &&
    a.roles.join(',') === b.roles.join(',')
  );
}

/** Người dùng có vai trò quản trị (chỉ để hiện/ẩn giao diện; quyền thật do server kiểm tra). */
export function isAdmin(user: User | null): boolean {
  return user?.roles.includes('ROLE_ADMIN') ?? false;
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null);
  const [loading, setLoading] = useState<boolean>(() => tokenStore.access() !== null);
  const [accountLocked, setAccountLocked] = useState(false);

  useEffect(() => {
    setSessionExpiredHandler(() => setUser(null));
    setAccountLockedHandler(() => {
      setUser(null);
      setAccountLocked(true);
    });
    return () => {
      setSessionExpiredHandler(null);
      setAccountLockedHandler(null);
    };
  }, []);

  // Khi đã đăng nhập, định kỳ (và khi quay lại tab) hỏi server: nếu admin vừa khoá tài khoản, request này nhận 403 ERR_104
  // và client tự hiện thông báo + đăng xuất; lỗi mạng bỏ qua (lần sau hỏi lại). Cũng làm mới hồ sơ nếu server đã đổi.
  const signedIn = user !== null;
  useEffect(() => {
    if (!signedIn) return;
    const check = () => {
      if (document.visibilityState === 'hidden') return;
      request<User>('/api/v1/users/me', { auth: true })
        // Chỉ thay user khi dữ liệu thật sự khác: giữ nguyên tham chiếu để lần kiểm tra định kỳ không làm cả ứng dụng render lại
        .then((fresh) => setUser((prev) => (prev && sameUser(prev, fresh) ? prev : fresh)))
        .catch(() => {});
    };
    const id = window.setInterval(check, SESSION_CHECK_MS);
    document.addEventListener('visibilitychange', check);
    return () => {
      window.clearInterval(id);
      document.removeEventListener('visibilitychange', check);
    };
  }, [signedIn]);

  // Khôi phục phiên: có token thì hỏi server ai đang đăng nhập (request tự refresh nếu access token hết hạn)
  useEffect(() => {
    if (tokenStore.access() === null) return;
    let cancelled = false;
    request<User>('/api/v1/users/me', { auth: true })
      .then((u) => !cancelled && setUser(u))
      .catch(() => !cancelled && setUser(null))
      .finally(() => !cancelled && setLoading(false));
    return () => {
      cancelled = true;
    };
  }, []);

  const login = useCallback(async (username: string, password: string) => {
    const res = await request<LoginResponse>('/api/v1/auth/login', { method: 'POST', body: { username, password } });
    tokenStore.set(res.accessToken, res.refreshToken);
    setUser(res.user);
  }, []);

  const register = useCallback(async (input: RegisterInput) => {
    await request<User>('/api/v1/auth/register', { method: 'POST', body: input });
  }, []);

  const logout = useCallback(async () => {
    try {
      await request<void>('/api/v1/auth/logout', { method: 'POST', auth: true });
    } catch {
      /* token đã hết hạn hoặc mất mạng: vẫn xoá phiên phía client */
    }
    tokenStore.clear();
    setUser(null);
  }, []);

  const dismissAccountLocked = useCallback(() => setAccountLocked(false), []);

  const value = useMemo(
    () => ({ user, loading, login, register, logout, setUser, accountLocked, dismissAccountLocked }),
    [user, loading, login, register, logout, accountLocked, dismissAccountLocked],
  );
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthValue {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used inside AuthProvider');
  return ctx;
}
