import { useEffect, useState, type ReactNode } from 'react'
import type { UrlStatus } from './types'

export function CopyButton({ text }: { text: string }) {
  const [done, setDone] = useState(false)
  async function copy() {
    try {
      await navigator.clipboard.writeText(text)
      setDone(true)
      setTimeout(() => setDone(false), 1500)
    } catch {
      window.prompt('Sao chép link:', text)
    }
  }
  return (
    <button type="button" className="btn ghost small" onClick={() => void copy()}>
      {done ? 'Đã chép' : 'Sao chép'}
    </button>
  )
}

const STATUS_LABEL: Record<UrlStatus, string> = {
  ACTIVE: 'Hoạt động',
  DISABLED: 'Đã tắt',
  EXPIRED: 'Hết hạn',
  BLOCKED: 'Bị chặn',
  DELETED: 'Đã xoá',
}

export function StatusBadge({ status }: { status: UrlStatus }) {
  return <span className={`badge ${status.toLowerCase()}`}>{STATUS_LABEL[status]}</span>
}

export function Modal({ title, onClose, children }: { title: string; onClose: () => void; children: ReactNode }) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose()
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])
  return (
    <div className="overlay" onClick={onClose}>
      <div className="card modal" role="dialog" aria-modal="true" aria-label={title} onClick={(e) => e.stopPropagation()}>
        <div className="modal-head">
          <h3>{title}</h3>
          <button className="btn ghost small" onClick={onClose} aria-label="Đóng">✕</button>
        </div>
        {children}
      </div>
    </div>
  )
}
