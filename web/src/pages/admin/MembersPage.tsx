import { keepPreviousData, useMutation, useQuery } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { useSearchParams } from 'react-router';
import { api, ApiError, call, type Role, type Schemas } from '../../api/client';
import { keys, queryClient } from '../../api/queryClient';
import { errorMessage } from '../../i18n/errors';
import { useI18n } from '../../i18n/i18n';
import { SettingsView } from '../../layouts/SettingsView';
import { useUser } from '../../session/session';
import { Avatar, HorizontalScroll, RolePill, roleClass } from '../../ui/bits';
import { Callout } from '../../ui/forms';
import { Icon, Spinner } from '../../ui/Icon';
import { Menu, MenuItem, MenuSeparator } from '../../ui/Menu';
import { useNotify } from '../../ui/Toast';
import { ConfirmDialog, InviteDialog, ResetLinkDialog } from './MemberDialogs';

type Member = Schemas['MemberView'];
type Confirmation = { kind: 'disable' | 'remove'; member: Member };

const PAGE_SIZE = 50;
const ROLES: Role[] = ['MEMBER', 'ADMIN', 'OWNER'];

function useDebounced(value: string, delay: number): string {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const timer = window.setTimeout(() => setDebounced(value), delay);
    return () => window.clearTimeout(timer);
  }, [value, delay]);
  return debounced;
}

function membersQuery(q: string, page: number) {
  return {
    queryKey: keys.members(q, page),
    queryFn: () => call(() => api.GET('/api/v1/admin/members', { params: { query: { q: q || undefined, page, size: PAGE_SIZE } } })),
  };
}

/** Administration › Members: roles, deactivation, removal, reset links and invitations. */
export function MembersPage() {
  const i18n = useI18n();
  const { t } = i18n;
  const notify = useNotify();
  const viewer = useUser();
  const [params, setParams] = useSearchParams();
  const [search, setSearch] = useState('');
  const [paging, setPaging] = useState({ q: '', page: 0 });
  const [confirmation, setConfirmation] = useState<Confirmation | null>(null);
  const [resetLink, setResetLink] = useState<{ name: string; link: Schemas['CreatedLink'] } | null>(null);
  const q = useDebounced(search.trim(), 250);
  const inviting = params.get('open') === 'invite';
  // A new search starts again from the first page.
  const page = paging.q === q ? paging.page : 0;
  const setPage = (next: number) => setPaging({ q, page: next });

  const everyone = useQuery(membersQuery('', 0));
  const members = useQuery({ ...membersQuery(q, page), placeholderData: keepPreviousData });
  const invitations = useQuery({ queryKey: keys.invitations, queryFn: () => call(() => api.GET('/api/v1/admin/invitations')) });

  const refresh = () => {
    void queryClient.invalidateQueries({ queryKey: keys.allMembers });
    void queryClient.invalidateQueries({ queryKey: keys.invitations });
  };

  const changeRole = useMutation({
    mutationFn: ({ member, role }: { member: Member; role: Role }) =>
      call(() => api.PATCH('/api/v1/admin/members/{id}', { params: { path: { id: member.id } }, body: { role } })),
    onSuccess: (member) => notify(t('members.roleUpdated', { role: t(`role.${member.role as Role}`) })),
    onError: (error) => notify(errorMessage(i18n, error)),
    onSettled: refresh,
  });

  const enable = useMutation({
    mutationFn: (member: Member) => call(() => api.POST('/api/v1/admin/members/{id}/enable', { params: { path: { id: member.id } } })),
    onSuccess: () => notify(t('members.enabled')),
    onError: (error) => notify(errorMessage(i18n, error)),
    onSettled: refresh,
  });

  const confirmAction = useMutation({
    mutationFn: ({ kind, member }: Confirmation) => kind === 'disable'
      ? call(() => api.POST('/api/v1/admin/members/{id}/disable', { params: { path: { id: member.id } } }))
      : call(() => api.DELETE('/api/v1/admin/members/{id}', { params: { path: { id: member.id } } })),
    onSuccess: (_, { kind }) => {
      setConfirmation(null);
      notify(t(kind === 'disable' ? 'members.disabled' : 'members.removed'));
    },
    onSettled: refresh,
  });

  const generateLink = useMutation({
    mutationFn: (member: Member) => call(() => api.POST('/api/v1/admin/members/{id}/password-reset-link', { params: { path: { id: member.id } } })),
    onSuccess: (link, member) => setResetLink({ name: member.displayName, link }),
    onError: (error) => {
      const disabled = error instanceof ApiError && error.code === 'account_disabled';
      notify(disabled ? t('members.resetDisabled') : errorMessage(i18n, error));
    },
  });

  const closeInvite = () => {
    const next = new URLSearchParams(params);
    next.delete('open');
    setParams(next, { replace: true });
  };

  const total = everyone.data?.total ?? 0;
  const result = members.data;
  const rows = result?.items ?? [];
  const pages = result ? Math.ceil(result.total / PAGE_SIZE) : 0;

  return (
    <SettingsView
      section="members"
      title={t('admin.members')}
      wide
      intro={everyone.data ? i18n.tp('members.intro', total) : undefined}
    >
      <div className="toolbar-row">
        <div className="search-box">
          <Icon name="search" />
          <input
            type="search"
            placeholder={t('members.search')}
            aria-label={t('members.search')}
            value={search}
            onChange={(event) => setSearch(event.target.value)}
          />
          {members.isFetching && q && <Spinner />}
        </div>
        <button type="button" className="btn btn-primary" onClick={() => setParams({ open: 'invite' })}>
          <Icon name="plus" />
          {t('members.invite')}
        </button>
      </div>

      {members.isError && <div style={{ marginTop: 12 }}><Callout tone="error" role="alert">{errorMessage(i18n, members.error)}</Callout></div>}

      <div className="set-box is-flush">
        <HorizontalScroll stickyFirstColumn>
          <table className="grid" style={{ minWidth: 720 }}>
            <thead>
              <tr>
                <th className="rn"><span className="sr-only">#</span></th>
                <th className="primary"><span className="th"><Icon name="user" />{t('members.col.member')}</span></th>
                <th><span className="th"><Icon name="mail" />{t('members.col.email')}</span></th>
                <th><span className="th"><Icon name="tag" />{t('members.col.role')}</span></th>
                <th><span className="th"><Icon name="tag" />{t('members.col.status')}</span></th>
                <th><span className="th"><Icon name="clock" />{t('members.col.lastLogin')}</span></th>
                <th><span className="sr-only">{t('members.col.actions')}</span></th>
              </tr>
            </thead>
            <tbody>
              {rows.map((member, index) => (
                <tr key={member.id} className={member.status === 'DISABLED' ? 'is-disabled' : undefined}>
                  <td className="rn">{page * PAGE_SIZE + index + 1}</td>
                  <td className="primary">
                    <span className="who">
                      <Avatar name={member.displayName} seed={member.id} />
                      <span>{member.displayName}{member.self && <span className="muted"> {t('members.you')}</span>}</span>
                    </span>
                  </td>
                  <td style={{ maxWidth: 240 }} title={member.email}>{member.email}</td>
                  <td className="cell-menu" style={{ textAlign: 'left', width: 'auto' }}>
                    {member.manageable ? (
                      <Menu
                        triggerClassName={`pill ${roleClass(member.role as Role)}`}
                        label={t('members.roleOf', { name: member.displayName })}
                        trigger={<>{t(`role.${member.role as Role}`)}<Icon name="chevron" /></>}
                      >
                        {ROLES.filter((role) => role !== 'OWNER' || viewer.isOwner).map((role) => (
                          <MenuItem
                            key={role}
                            checked={member.role === role}
                            onSelect={() => member.role !== role && changeRole.mutate({ member, role })}
                          >
                            <span className={`pill ${roleClass(role)}`}>{t(`role.${role}`)}</span>
                          </MenuItem>
                        ))}
                      </Menu>
                    ) : (
                      <RolePill role={member.role} />
                    )}
                  </td>
                  <td>
                    <span className={member.status === 'ACTIVE' ? 'pill p-green' : 'pill p-gray'}>
                      {t(member.status === 'ACTIVE' ? 'status.ACTIVE' : 'status.DISABLED')}
                    </span>
                  </td>
                  <td>{member.lastLoginAt ? i18n.relative(member.lastLoginAt) : <span className="muted">{t('common.never')}</span>}</td>
                  <td className="cell-menu">
                    {member.manageable && (
                      <Menu align="end" label={t('members.actionsFor', { name: member.displayName })} trigger={<Icon name="dots" />}>
                        {member.status === 'ACTIVE' && (
                          <MenuItem icon="key" onSelect={() => generateLink.mutate(member)}>{t('members.resetLink')}</MenuItem>
                        )}
                        {member.status === 'ACTIVE' ? (
                          <MenuItem icon="x" onSelect={() => { confirmAction.reset(); setConfirmation({ kind: 'disable', member }); }}>{t('members.disable')}</MenuItem>
                        ) : (
                          <MenuItem icon="check" onSelect={() => enable.mutate(member)}>{t('members.enable')}</MenuItem>
                        )}
                        <MenuSeparator />
                        <MenuItem icon="trash" danger onSelect={() => { confirmAction.reset(); setConfirmation({ kind: 'remove', member }); }}>
                          {t('members.remove')}
                        </MenuItem>
                      </Menu>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </HorizontalScroll>
        {members.isPending && <div className="grid-empty" role="status"><Spinner /><span className="sr-only">{t('common.loading')}</span></div>}
        {result && rows.length === 0 && <p className="grid-empty">{t('members.noResult', { q })}</p>}
        {result && rows.length > 0 && (
          <div className="grid-foot">
            <span>{i18n.tp('members.shown', rows.length, { total: result.total })}</span>
            {pages > 1 && (
              <span className="pager">
                <button type="button" className="btn" disabled={page === 0} onClick={() => setPage(page - 1)}>
                  <Icon name="chevron-left" />{t('members.previous')}
                </button>
                <button type="button" className="btn" disabled={page + 1 >= pages} onClick={() => setPage(page + 1)}>
                  {t('members.next')}<Icon name="chevron-right" />
                </button>
              </span>
            )}
          </div>
        )}
      </div>

      <PendingInvitations invitations={invitations.data ?? []} onChanged={refresh} />

      {inviting && <InviteDialog canInviteAdmins onClose={closeInvite} />}
      {resetLink && <ResetLinkDialog name={resetLink.name} link={resetLink.link} onClose={() => setResetLink(null)} />}
      {confirmation && (
        <ConfirmDialog
          title={t(confirmation.kind === 'disable' ? 'members.disableTitle' : 'members.removeTitle', { name: confirmation.member.displayName })}
          body={t(confirmation.kind === 'disable' ? 'members.disableBody' : 'members.removeBody', { name: confirmation.member.displayName })}
          confirm={t(confirmation.kind === 'disable' ? 'members.disable' : 'members.remove')}
          pending={confirmAction.isPending}
          error={confirmAction.error}
          onConfirm={() => confirmAction.mutate(confirmation)}
          onClose={() => setConfirmation(null)}
        />
      )}
    </SettingsView>
  );
}

function PendingInvitations({ invitations, onChanged }: { invitations: Schemas['InvitationView'][]; onChanged: () => void }) {
  const i18n = useI18n();
  const { t } = i18n;
  const notify = useNotify();
  const revoke = useMutation({
    mutationFn: (id: string) => call(() => api.DELETE('/api/v1/admin/invitations/{id}', { params: { path: { id } } })),
    onSuccess: () => notify(t('invitations.revoked')),
    onError: (error) => notify(errorMessage(i18n, error)),
    onSettled: onChanged,
  });
  if (invitations.length === 0) return null;
  return (
    <section className="set-sec" aria-labelledby="pending-invitations">
      <h2 id="pending-invitations">{t('invitations.pending')}</h2>
      <p className="help">{t('invitations.help')}</p>
      <ul className="list" style={{ marginTop: 12 }}>
        {invitations.map((invitation) => {
          const label = invitation.email ?? t('invitations.noEmail');
          const date = i18n.date(invitation.expiresAt);
          return (
            <li key={invitation.id}>
              <div className="list-row">
                <span className="cat-ico"><Icon name={invitation.email ? 'mail' : 'link'} /></span>
                <div className="list-main">
                  <div className="list-title">{label}<RolePill role={invitation.role} /></div>
                  <div className="list-sub">
                    {invitation.createdBy ? t('invitations.meta', { name: invitation.createdBy, date }) : t('invitations.metaAnonymous', { date })}
                  </div>
                </div>
                <button
                  type="button"
                  className="btn"
                  disabled={revoke.isPending && revoke.variables === invitation.id}
                  aria-label={t('invitations.revokeOf', { name: label })}
                  onClick={() => revoke.mutate(invitation.id)}
                >
                  {t('invitations.revoke')}
                </button>
              </div>
            </li>
          );
        })}
      </ul>
    </section>
  );
}
