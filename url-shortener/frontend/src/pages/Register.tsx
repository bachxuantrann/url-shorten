import { useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { api, ApiError } from '../api'
import { useAuth } from '../auth'

export default function Register() {
  const { login } = useAuth()
  const [f, setF] = useState({ username: '', email: '', fullName: '', password: '' })
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)

  const set = (k: keyof typeof f) => (e: React.ChangeEvent<HTMLInputElement>) => setF({ ...f, [k]: e.target.value })

  async function submit(e: FormEvent) {
    e.preventDefault()
    setBusy(true)
    setError('')
    try {
      await api.register({ ...f, username: f.username.trim(), email: f.email.trim(), fullName: f.fullName.trim() })
      await login(f.username.trim(), f.password)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Có lỗi xảy ra')
      setBusy(false)
    }
  }

  return (
    <form onSubmit={submit} className="card form">
      <h2>Đăng ký</h2>
      <label>
        Tên đăng nhập
        <input value={f.username} onChange={set('username')} minLength={4} maxLength={50} pattern="[a-zA-Z0-9._\-]+" autoComplete="username" required />
      </label>
      <label>
        Email
        <input type="email" value={f.email} onChange={set('email')} autoComplete="email" required />
      </label>
      <label>
        Họ tên
        <input value={f.fullName} onChange={set('fullName')} maxLength={100} autoComplete="name" required />
      </label>
      <label>
        Mật khẩu
        <input type="password" value={f.password} onChange={set('password')} minLength={8} maxLength={100} autoComplete="new-password" required />
        <small className="muted">Ít nhất 8 ký tự, có chữ hoa, chữ thường và số.</small>
      </label>
      {error && <p className="error" role="alert">{error}</p>}
      <button className="btn" disabled={busy}>{busy ? 'Đang tạo…' : 'Tạo tài khoản'}</button>
      <p className="muted">Đã có tài khoản? <Link to="/login">Đăng nhập</Link></p>
    </form>
  )
}
