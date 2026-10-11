import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { request, setSessionExpiredHandler, tokenStore } from '../api/client';
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
}

const AuthContext = createContext<AuthValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null);
  const [loading, setLoading] = useState<boolean>(() => tokenStore.access() !== null);

  useEffect(() => {
    setSessionExpiredHandler(() => setUser(null));
    return () => setSessionExpiredHandler(null);
  }, []);

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

  const value = useMemo(() => ({ user, loading, login, register, logout, setUser }), [user, loading, login, register, logout]);
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthValue {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used inside AuthProvider');
  return ctx;
}
