import type { ReactNode } from 'react';

interface EmptyStateProps {
  title: string;
  text: string;
  action?: ReactNode;
}

/** Trạng thái rỗng có minh hoạ SVG (vẽ bằng màu chủ đạo nên tự đổi theo sáng/tối) và nút hành động. */
export function EmptyState({ title, text, action }: EmptyStateProps) {
  return (
    <div className="empty">
      <svg className="empty-art" viewBox="0 0 120 120" fill="none" aria-hidden="true">
        <circle cx="60" cy="60" r="52" fill="currentColor" opacity="0.08" />
        <rect x="22" y="40" width="76" height="40" rx="12" fill="var(--surface)" stroke="currentColor" strokeWidth="2.5" />
        <rect x="32" y="54" width="34" height="6" rx="3" fill="currentColor" opacity="0.35" />
        <rect x="32" y="64" width="22" height="6" rx="3" fill="currentColor" opacity="0.2" />
        <circle cx="84" cy="60" r="9" fill="currentColor" />
        <path d="M80.5 60h7M85 56.5l3.5 3.5-3.5 3.5" stroke="var(--on-primary)" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
        <path d="M30 28l4 6M60 22v8M90 28l-4 6" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" opacity="0.5" />
      </svg>
      <h3>{title}</h3>
      <p>{text}</p>
      {action}
    </div>
  );
}
