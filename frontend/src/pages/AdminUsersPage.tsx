import { useCallback, useState } from 'react';
import { Lock, LockOpen } from 'lucide-react';
import { listAdminUsers, lockUser, unlockUser } from '../api/admin';
import type { AdminUser, UserStatus } from '../api/types';
import { useAuth } from '../auth/AuthProvider';
import { AdminHead, ListSkeleton, LoadError, Pager, SearchBox } from '../components/admin';
import { Button } from '../components/ui/Button';
import { EmptyState } from '../components/ui/EmptyState';
import { Modal } from '../components/ui/Modal';
import { useToast } from '../components/ui/Toast';
import { useDocumentTitle } from '../hooks/useDocumentTitle';
import { useMediaQuery } from '../hooks/useMediaQuery';
import { usePagedList } from '../hooks/usePagedList';
import { useServerTime } from '../hooks/useServerTime';
import { useSubmit } from '../hooks/useSubmit';
import { useI18n } from '../i18n/I18nProvider';
import type { MessageKey } from '../i18n/messages';
import { useDebounced } from '../utils/useDebounced';

const PAGE_SIZE = 10;
const STATUSES: UserStatus[] = ['ACTIVE', 'INACTIVE', 'BANNED'];

/** Quản trị: danh sách người dùng, tìm kiếm, lọc theo trạng thái, khoá / mở khoá tài khoản (không tự khoá chính mình). */
export function AdminUsersPage() {
  const { t, language } = useI18n();
  useDocumentTitle(t('nav.admin.users'));
  const { user: me } = useAuth();
  const compact = useMediaQuery('(max-width: 1100px)');
  const { ready: zoneReady } = useServerTime();

  const [keyword, setKeyword] = useState('');
  const [status, setStatus] = useState<UserStatus | ''>('');
  const [page, setPage] = useState(0);
  const [target, setTarget] = useState<{ kind: 'lock' | 'unlock'; user: AdminUser } | null>(null);

  const debouncedKeyword = useDebounced(keyword.trim());
  const load = useCallback(
    () => listAdminUsers({ keyword: debouncedKeyword || undefined, status: status || undefined, page, size: PAGE_SIZE }),
    [debouncedKeyword, status, page],
  );
  const { data, loading, error, reload } = usePagedList(load, () => setPage((p) => Math.max(p - 1, 0)));
  const content = data?.content ?? [];
  const filtered = debouncedKeyword !== '' || status !== '';

  const onAction = (kind: 'lock' | 'unlock', user: AdminUser) => setTarget({ kind, user });

  return (
    <div className="stack">
      <AdminHead title={t('admin.users.title')} sub={data ? t('admin.users.count', { count: data.totalElements.toLocaleString(language) }) : undefined} />

      <section className="card" aria-label={t('admin.users.title')}>
        <div className="toolbar">
          <SearchBox
            label={t('admin.users.search')}
            value={keyword}
            onChange={(v) => {
              setKeyword(v);
              setPage(0);
            }}
          />
          <div className="segmented" role="group" aria-label={t('admin.users.col.status')}>
            {(['', ...STATUSES] as (UserStatus | '')[]).map((s) => (
              <button
                key={s || 'all'}
                type="button"
                aria-pressed={status === s}
                onClick={() => {
                  setStatus(s);
                  setPage(0);
                }}
              >
                {s === '' ? t('links.filter.all') : t(`admin.user.status.${s}` as MessageKey)}
              </button>
            ))}
          </div>
        </div>

        {error && <LoadError message={error} onRetry={reload} />}

        {(loading && !data) || !zoneReady ? (
          <ListSkeleton />
        ) : data && content.length === 0 ? (
          <EmptyState
            title={t('admin.users.empty.title')}
            text={t('admin.users.empty.text')}
            action={
              filtered ? (
                <Button
                  variant="secondary"
                  onClick={() => {
                    setKeyword('');
                    setStatus('');
                    setPage(0);
                  }}
                >
                  {t('links.clearFilters')}
                </Button>
              ) : undefined
            }
          />
        ) : (
          data && (
            <div aria-busy={loading}>
              {compact ? (
                <div className="cards-mobile">
                  {content.map((u) => (
                    <UserCard key={u.id} user={u} isMe={u.id === me?.id} onAction={onAction} />
                  ))}
                </div>
              ) : (
                <div className="table-scroll">
                  <table className="data-table">
                    <thead>
                      <tr>
                        <th scope="col">{t('admin.users.col.user')}</th>
                        <th scope="col" className="col-status">{t('admin.users.col.status')}</th>
                        <th scope="col" className="col-date">{t('admin.users.col.lastLogin')}</th>
                        <th scope="col" className="col-date">{t('admin.users.col.joined')}</th>
                        <th scope="col" className="col-action col-action-wide">
                          <span className="sr-only">{t('admin.col.actions')}</span>
                        </th>
                      </tr>
                    </thead>
                    <tbody>
                      {content.map((u) => (
                        <UserRow key={u.id} user={u} isMe={u.id === me?.id} onAction={onAction} />
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
              <Pager data={data} count={content.length} onPage={setPage} />
            </div>
          )
        )}
      </section>

      <LockModal target={target} onClose={() => setTarget(null)} onDone={reload} />
    </div>
  );
}

interface ItemProps {
  user: AdminUser;
  isMe: boolean;
  onAction: (kind: 'lock' | 'unlock', user: AdminUser) => void;
}

function UserStatusBadge({ status }: { status: UserStatus }) {
  const { t } = useI18n();
  const tone = { ACTIVE: 'badge-success', INACTIVE: 'badge-warning', BANNED: 'badge-danger' }[status];
  return <span className={`badge ${tone}`}>{t(`admin.user.status.${status}` as MessageKey)}</span>;
}

function Identity({ user, isMe }: { user: AdminUser; isMe: boolean }) {
  const { t } = useI18n();
  const isAdmin = user.roles.includes('ROLE_ADMIN');
  return (
    <div className="link-main">
      <div className="link-short">
        <span className="truncate">{user.fullName || user.username}</span>
        {isAdmin && <span className="badge badge-primary">{t('admin.user.role.ROLE_ADMIN')}</span>}
        {isMe && <span className="badge">{t('admin.users.you')}</span>}
      </div>
      <div className="link-target truncate" title={user.email}>
        @{user.username} · {user.email}
      </div>
    </div>
  );
}

function ActionButton({ user, isMe, onAction }: ItemProps) {
  const { t } = useI18n();
  if (user.status === 'BANNED') {
    return (
      <Button variant="secondary" size="sm" onClick={() => onAction('unlock', user)} aria-label={`${t('admin.user.unlock')} ${user.username}`}>
        <LockOpen size={16} aria-hidden="true" />
        {t('admin.user.unlock')}
      </Button>
    );
  }
  return (
    <Button
      variant="secondary"
      size="sm"
      // Khoá chính mình sẽ khoá luôn quyền quản trị, nên không cho
      disabled={isMe}
      onClick={() => onAction('lock', user)}
      aria-label={`${t('admin.user.lock')} ${user.username}`}
    >
      <Lock size={16} aria-hidden="true" />
      {t('admin.user.lock')}
    </Button>
  );
}

function UserRow(props: ItemProps) {
  const { user } = props;
  const { t } = useI18n();
  const time = useServerTime();
  return (
    <tr>
      <td>
        <Identity user={user} isMe={props.isMe} />
      </td>
      <td>
        <UserStatusBadge status={user.status} />
      </td>
      <td className="muted small">{user.lastLoginAt ? time.formatDay(user.lastLoginAt) : t('admin.users.never')}</td>
      <td className="muted small">{time.formatDay(user.createdAt)}</td>
      <td className="col-actions">
        <ActionButton {...props} />
      </td>
    </tr>
  );
}

function UserCard(props: ItemProps) {
  const { user } = props;
  const { t } = useI18n();
  const time = useServerTime();
  return (
    <div className="link-card">
      <Identity user={user} isMe={props.isMe} />
      <div className="meta">
        <UserStatusBadge status={user.status} />
        <span>
          {t('admin.users.col.lastLogin')}: {user.lastLoginAt ? time.formatDay(user.lastLoginAt) : t('admin.users.never')}
        </span>
      </div>
      <div className="actions">
        <ActionButton {...props} />
      </div>
    </div>
  );
}

// ===========================
//  Xác nhận khoá / mở khoá
// ===========================

interface TargetProps {
  target: { kind: 'lock' | 'unlock'; user: AdminUser } | null;
  onClose: () => void;
  onDone: () => void;
}

function LockModal({ target, onClose, onDone }: TargetProps) {
  const { t } = useI18n();
  const title = target ? t(`admin.user.${target.kind}.title` as MessageKey, { username: target.user.username }) : '';
  return (
    <Modal open={target !== null} onClose={onClose} title={title}>
      {target && <LockBody kind={target.kind} user={target.user} onClose={onClose} onDone={onDone} />}
    </Modal>
  );
}

function LockBody({ kind, user, onClose, onDone }: { kind: 'lock' | 'unlock'; user: AdminUser; onClose: () => void; onDone: () => void }) {
  const { t } = useI18n();
  const toast = useToast();
  const { busy, error, run } = useSubmit();

  async function confirm() {
    const ok = await run(() => (kind === 'lock' ? lockUser(user.id) : unlockUser(user.id)));
    if (ok) {
      toast.success(t(kind === 'lock' ? 'toast.userLocked' : 'toast.userUnlocked'));
      onDone();
      onClose();
    }
  }

  return (
    <div className="form">
      {error?.message && (
        <div className="alert" role="alert">
          <span>{error.message}</span>
        </div>
      )}
      <p className="muted">{t(`admin.user.${kind}.text` as MessageKey)}</p>
      <div className="modal-foot" style={{ padding: 0 }}>
        <Button variant="secondary" onClick={onClose}>
          {t('common.cancel')}
        </Button>
        <Button variant={kind === 'lock' ? 'danger' : 'primary'} loading={busy} onClick={confirm}>
          {t(`admin.user.${kind}.confirm` as MessageKey)}
        </Button>
      </div>
    </div>
  );
}
