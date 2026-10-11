import { useCallback, useEffect, useState, type FormEvent } from 'react'
import { api, ApiError, toServerDateTime } from '../api'
import { CopyButton, Modal, StatusBadge } from '../components'
import type { Page, UrlItem, UrlStatus } from '../types'

const SIZE = 10

const errMsg = (e: unknown) => (e instanceof ApiError ? e.message : 'Có lỗi xảy ra')
const fmt = (s?: string) => (s ? new Date(s).toLocaleString('vi-VN') : '—')

export default function Dashboard() {
  const [data, setData] = useState<Page<UrlItem> | null>(null)
  const [page, setPage] = useState(0)
  const [keyword, setKeyword] = useState('')
  const [debounced, setDebounced] = useState('')
  const [status, setStatus] = useState<UrlStatus | ''>('')
  const [error, setError] = useState('')
  const [editing, setEditing] = useState<UrlItem | null>(null)
  const [pwFor, setPwFor] = useState<UrlItem | null>(null)
  const [qrFor, setQrFor] = useState<UrlItem | null>(null)

  useEffect(() => {
    const t = setTimeout(() => {
      setDebounced(keyword.trim())
      setPage(0)
    }, 300)
    return () => clearTimeout(t)
  }, [keyword])

  const load = useCallback(async () => {
    try {
      setError('')
      setData(await api.list({ keyword: debounced, status, page, size: SIZE, sortBy: 'createdAt', sortDir: 'desc' }))
    } catch (e) {
      setError(errMsg(e))
    }
  }, [debounced, status, page])

  useEffect(() => {
    void load()
  }, [load])

  async function act(fn: () => Promise<unknown>) {
    try {
      setError('')
      await fn()
      await load()
    } catch (e) {
      setError(errMsg(e))
    }
  }

  return (
    <>
      <CreateForm onCreated={() => (page === 0 ? load() : setPage(0))} />

      <section>
        <div className="row filters">
          <input placeholder="Tìm theo link gốc hoặc mã…" value={keyword} maxLength={100} onChange={(e) => setKeyword(e.target.value)} aria-label="Tìm kiếm" />
          <select value={status} onChange={(e) => { setStatus(e.target.value as UrlStatus | ''); setPage(0) }} aria-label="Lọc trạng thái">
            <option value="">Mọi trạng thái</option>
            <option value="ACTIVE">Hoạt động</option>
            <option value="DISABLED">Đã tắt</option>
            <option value="EXPIRED">Hết hạn</option>
            <option value="BLOCKED">Bị chặn</option>
          </select>
        </div>
        {error && <p className="error" role="alert">{error}</p>}

        {data && data.content.length === 0 && <p className="muted">Chưa có link nào.</p>}
        <ul className="links">
          {data?.content.map((u) => (
            <li key={u.shortCode} className="card link">
              <div className="link-main">
                <div>
                  <a href={u.shortUrl} target="_blank" rel="noopener noreferrer" className="short">{u.shortUrl}</a>{' '}
                  <StatusBadge status={u.status} />
                  {u.passwordProtected && <span className="badge pw">Có mật khẩu</span>}
                </div>
                <div className="orig" title={u.originalUrl}>{u.originalUrl}</div>
                <div className="muted small-text">
                  {u.clickCount} lượt nhấp · tạo {fmt(u.createdAt)} · hết hạn {fmt(u.expiresAt)}
                </div>
                {u.status === 'BLOCKED' && <div className="error small-text">Bị chặn: {u.blockedReason ?? 'vi phạm chính sách'}</div>}
              </div>
              <div className="actions">
                <CopyButton text={u.shortUrl} />
                <button className="btn ghost small" onClick={() => setQrFor(u)}>QR</button>
                {u.status !== 'BLOCKED' && (
                  <>
                    <button className="btn ghost small" onClick={() => setEditing(u)}>Sửa</button>
                    <button className="btn ghost small" onClick={() => setPwFor(u)}>Mật khẩu</button>
                    {u.status === 'ACTIVE' && <button className="btn ghost small" onClick={() => void act(() => api.disable(u.shortCode))}>Tắt</button>}
                    {u.status === 'DISABLED' && <button className="btn ghost small" onClick={() => void act(() => api.enable(u.shortCode))}>Bật</button>}
                    <button
                      className="btn danger small"
                      onClick={() => window.confirm(`Xoá link ${u.shortCode}? Mã này sẽ không được cấp lại.`) && void act(() => api.remove(u.shortCode))}
                    >
                      Xoá
                    </button>
                  </>
                )}
              </div>
            </li>
          ))}
        </ul>

        {data && data.totalPages > 1 && (
          <div className="row pager">
            <button className="btn ghost small" disabled={!data.hasPrevious} onClick={() => setPage(page - 1)}>‹ Trước</button>
            <span className="muted">Trang {data.page + 1} / {data.totalPages}</span>
            <button className="btn ghost small" disabled={!data.hasNext} onClick={() => setPage(page + 1)}>Sau ›</button>
          </div>
        )}
      </section>

      {editing && <EditModal item={editing} onClose={() => setEditing(null)} onSaved={() => { setEditing(null); void load() }} />}
      {pwFor && <PasswordModal item={pwFor} onClose={() => setPwFor(null)} onSaved={() => { setPwFor(null); void load() }} />}
      {qrFor && <QrModal item={qrFor} onClose={() => setQrFor(null)} />}
    </>
  )
}

function CreateForm({ onCreated }: { onCreated: () => void }) {
  const [f, setF] = useState({ originalUrl: '', alias: '', password: '', expiresAt: '' })
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const set = (k: keyof typeof f) => (e: React.ChangeEvent<HTMLInputElement>) => setF({ ...f, [k]: e.target.value })

  async function submit(e: FormEvent) {
    e.preventDefault()
    setBusy(true)
    setError('')
    try {
      await api.create({
        originalUrl: f.originalUrl.trim(),
        alias: f.alias.trim() || undefined,
        password: f.password || undefined,
        expiresAt: toServerDateTime(f.expiresAt),
      })
      setF({ originalUrl: '', alias: '', password: '', expiresAt: '' })
      onCreated()
    } catch (err) {
      setError(errMsg(err))
    } finally {
      setBusy(false)
    }
  }

  return (
    <form onSubmit={submit} className="card form create">
      <h2>Tạo link mới</h2>
      <label>
        Đường dẫn gốc
        <input type="url" required placeholder="https://…" value={f.originalUrl} onChange={set('originalUrl')} />
      </label>
      <div className="grid3">
        <label>
          Alias (tuỳ chọn)
          <input value={f.alias} onChange={set('alias')} minLength={3} maxLength={32} pattern="[0-9a-zA-Z_\-]+" placeholder="vd: khuyen-mai" />
        </label>
        <label>
          Mật khẩu (tuỳ chọn)
          <input type="password" value={f.password} onChange={set('password')} minLength={4} maxLength={64} autoComplete="new-password" />
        </label>
        <label>
          Hết hạn (tuỳ chọn)
          <input type="datetime-local" value={f.expiresAt} onChange={set('expiresAt')} />
        </label>
      </div>
      {error && <p className="error" role="alert">{error}</p>}
      <div><button className="btn" disabled={busy}>{busy ? 'Đang tạo…' : 'Tạo link'}</button></div>
    </form>
  )
}

function EditModal({ item, onClose, onSaved }: { item: UrlItem; onClose: () => void; onSaved: () => void }) {
  const [url, setUrl] = useState(item.originalUrl)
  // Cập nhật thay thế cả đích lẫn hạn: để trống nghĩa là bỏ hạn.
  const [exp, setExp] = useState(item.expiresAt ? item.expiresAt.slice(0, 16) : '')
  const [error, setError] = useState('')

  async function submit(e: FormEvent) {
    e.preventDefault()
    try {
      await api.update(item.shortCode, { originalUrl: url.trim(), expiresAt: toServerDateTime(exp) })
      onSaved()
    } catch (err) {
      setError(errMsg(err))
    }
  }

  return (
    <Modal title={`Sửa ${item.shortCode}`} onClose={onClose}>
      <form onSubmit={submit} className="form">
        <label>Đường dẫn gốc<input type="url" required value={url} onChange={(e) => setUrl(e.target.value)} /></label>
        <label>
          Hết hạn (để trống = không hết hạn)
          <input type="datetime-local" value={exp} onChange={(e) => setExp(e.target.value)} />
        </label>
        {error && <p className="error" role="alert">{error}</p>}
        <button className="btn">Lưu</button>
      </form>
    </Modal>
  )
}

function PasswordModal({ item, onClose, onSaved }: { item: UrlItem; onClose: () => void; onSaved: () => void }) {
  const [pw, setPw] = useState('')
  const [error, setError] = useState('')

  async function save(e: FormEvent) {
    e.preventDefault()
    try {
      await api.setPassword(item.shortCode, pw)
      onSaved()
    } catch (err) {
      setError(errMsg(err))
    }
  }
  async function remove() {
    try {
      await api.removePassword(item.shortCode)
      onSaved()
    } catch (err) {
      setError(errMsg(err))
    }
  }

  return (
    <Modal title={`Mật khẩu ${item.shortCode}`} onClose={onClose}>
      <form onSubmit={save} className="form">
        <label>
          {item.passwordProtected ? 'Đổi mật khẩu' : 'Đặt mật khẩu'}
          <input type="password" required minLength={4} maxLength={64} value={pw} onChange={(e) => setPw(e.target.value)} autoComplete="new-password" />
        </label>
        <small className="muted">Link có mật khẩu chỉ mở được qua API unlock; trình duyệt mở thẳng sẽ nhận lỗi 403.</small>
        {error && <p className="error" role="alert">{error}</p>}
        <div className="row">
          <button className="btn">Lưu</button>
          {item.passwordProtected && <button type="button" className="btn danger" onClick={() => void remove()}>Gỡ mật khẩu</button>}
        </div>
      </form>
    </Modal>
  )
}

function QrModal({ item, onClose }: { item: UrlItem; onClose: () => void }) {
  const [src, setSrc] = useState('')
  const [error, setError] = useState('')

  useEffect(() => {
    let url = ''
    let cancelled = false
    api
      .qr(item.shortCode)
      .then((u) => {
        url = u
        if (cancelled) URL.revokeObjectURL(u)
        else setSrc(u)
      })
      .catch((e) => setError(errMsg(e)))
    return () => {
      cancelled = true
      if (url) URL.revokeObjectURL(url)
    }
  }, [item.shortCode])

  return (
    <Modal title={`Mã QR ${item.shortCode}`} onClose={onClose}>
      {error ? <p className="error">{error}</p> : src ? <img src={src} alt={`Mã QR của ${item.shortUrl}`} className="qr" /> : <p className="muted">Đang tải…</p>}
      {src && <a className="btn" href={src} download={`qr-${item.shortCode}.png`}>Tải PNG</a>}
    </Modal>
  )
}
