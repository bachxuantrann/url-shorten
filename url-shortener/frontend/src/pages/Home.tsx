import { useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { api, ApiError } from '../api'
import { useAuth } from '../auth'
import type { UrlItem } from '../types'
import { CopyButton } from '../components'

/** Trang chủ: rút gọn nhanh không cần tài khoản (giới hạn theo IP ở server). */
export default function Home() {
  const { user } = useAuth()
  const [url, setUrl] = useState('')
  const [result, setResult] = useState<UrlItem | null>(null)
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)

  async function submit(e: FormEvent) {
    e.preventDefault()
    setBusy(true)
    setError('')
    setResult(null)
    try {
      setResult(user ? await api.create({ originalUrl: url.trim() }) : await api.createAnonymous({ originalUrl: url.trim() }))
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Có lỗi xảy ra')
    } finally {
      setBusy(false)
    }
  }

  return (
    <section className="hero">
      <h1>Rút gọn link trong một giây</h1>
      <p className="muted">Dán đường dẫn dài, nhận link ngắn để chia sẻ.</p>
      <form onSubmit={submit} className="row">
        <input
          type="url"
          required
          placeholder="https://example.com/duong-dan-rat-dai"
          value={url}
          onChange={(e) => setUrl(e.target.value)}
          aria-label="Đường dẫn cần rút gọn"
        />
        <button className="btn" disabled={busy}>
          {busy ? 'Đang tạo…' : 'Rút gọn'}
        </button>
      </form>
      {error && <p className="error" role="alert">{error}</p>}
      {result && (
        <div className="card result">
          <a href={result.shortUrl} target="_blank" rel="noopener noreferrer">
            {result.shortUrl}
          </a>
          <CopyButton text={result.shortUrl} />
        </div>
      )}
      {!user && (
        <p className="muted">
          <Link to="/register">Đăng ký</Link> để đặt alias riêng, mật khẩu, ngày hết hạn, mã QR và quản lý link.
        </p>
      )}
    </section>
  )
}
