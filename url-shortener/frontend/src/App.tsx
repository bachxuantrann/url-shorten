import { Link, Navigate, Route, Routes } from 'react-router-dom'
import { useAuth } from './auth'
import Dashboard from './pages/Dashboard'
import Home from './pages/Home'
import Login from './pages/Login'
import Register from './pages/Register'

export default function App() {
  const { user, loading, logout } = useAuth()

  return (
    <>
      <header className="topbar">
        <Link to="/" className="brand">
          Rút gọn link
        </Link>
        <nav>
          {user ? (
            <>
              <Link to="/dashboard">Link của tôi</Link>
              <span className="muted">{user.username}</span>
              <button className="btn ghost" onClick={() => void logout()}>
                Đăng xuất
              </button>
            </>
          ) : (
            <>
              <Link to="/login">Đăng nhập</Link>
              <Link to="/register" className="btn">
                Đăng ký
              </Link>
            </>
          )}
        </nav>
      </header>
      <main className="container">
        {loading ? (
          <p className="muted">Đang tải…</p>
        ) : (
          <Routes>
            <Route path="/" element={<Home />} />
            <Route path="/login" element={user ? <Navigate to="/dashboard" replace /> : <Login />} />
            <Route path="/register" element={user ? <Navigate to="/dashboard" replace /> : <Register />} />
            <Route path="/dashboard" element={user ? <Dashboard /> : <Navigate to="/login" replace />} />
            <Route path="*" element={<Navigate to="/" replace />} />
          </Routes>
        )}
      </main>
    </>
  )
}
