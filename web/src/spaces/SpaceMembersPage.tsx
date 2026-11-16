import { useMutation, useQuery } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router';
import { api, ApiError, call, type Schemas } from '../api/client';
import { keys, queryClient } from '../api/queryClient';
import { errorMessage } from '../i18n/errors';
import { useI18n } from '../i18n/i18n';
import { View } from '../layouts/AppShell';
import { ConfirmDialog } from '../pages/admin/MemberDialogs';
import { Avatar, HorizontalScroll, useDebounced, useDocumentTitle } from '../ui/bits';
import { Callout, SelectField } from '../ui/forms';
import { Icon, Spinner } from '../ui/Icon';
import { Menu, MenuItem } from '../ui/Menu';
import { Modal } from '../ui/Modal';
import { useNotify } from '../ui/Toast';
import { ArchivedNotice } from './SpaceLibraryPage';
import { spaceCrumbs } from './SpaceRoute';
import { canManage, refreshSpaces, SPACE_ROLES, spaceRoleClass, SpaceRolePill, useSpace, type Space, type SpaceRole } from './spaces';

type Member = Schemas['SpaceMemberView'];

/**
 * The members of a space. Everyone who sees the space sees them; its maintainers add members of
 * the organization, change their roles and remove them; anyone can leave.
 */
export function SpaceMembersPage() {
  const i18n = useI18n();
  const { t } = i18n;
  const notify = useNotify();
  const navigate = useNavigate();
  const space = useSpace();
  const [adding, setAdding] = useState(false);
  const [removing, setRemoving] = useState<Member | null>(null);
  const manager = canManage(space) && !space.archivedAt;
  useDocumentTitle(t('spaceMembers.title', { name: space.name }));

  const members = useQuery({
    queryKey: keys.spaceMembers(space.id),
    queryFn: () => call(() => api.GET('/api/v1/spaces/{spaceId}/members', { params: { path: { spaceId: space.id } } })),
  });

  const refresh = () => {
    void queryClient.invalidateQueries({ queryKey: keys.spaceMembers(space.id) });
    void queryClient.invalidateQueries({ queryKey: keys.allSpaceCandidates(space.id) });
    void refreshSpaces();
  };

  const changeRole = useMutation({
    mutationFn: ({ member, role }: { member: Member; role: SpaceRole }) =>
      call(() => api.PATCH('/api/v1/spaces/{spaceId}/members/{userId}', {
        params: { path: { spaceId: space.id, userId: member.id } },
        body: { role },
      })),
    onSuccess: (member) => notify(t('spaceMembers.roleUpdated', { role: t(`spaceRole.${member.role as SpaceRole}`) })),
    onError: (error) => notify(errorMessage(i18n, error)),
    onSettled: refresh,
  });

  const remove = useMutation({
    mutationFn: (member: Member) => call(() => api.DELETE('/api/v1/spaces/{spaceId}/members/{userId}', {
      params: { path: { spaceId: space.id, userId: member.id } },
    })),
    onSuccess: (_, member) => {
      setRemoving(null);
      if (member.self) {
        notify(t('spaceMembers.left', { name: space.name }));
        // The space is no longer theirs to see: "Back" does not lead to it.
        navigate('/', { replace: true });
      } else {
        notify(t('spaceMembers.removed'));
      }
    },
    onSettled: refresh,
  });

  const rows = members.data ?? [];
  return (
    <View
      crumbs={spaceCrumbs(t('nav.spaces'), space, t('nav.spaceMembers'))}
      actions={manager ? (
        <button type="button" className="btn btn-primary" onClick={() => setAdding(true)}>
          <Icon name="plus" />
          <span className="btn-label">{t('spaceMembers.add')}</span>
        </button>
      ) : undefined}
    >
      <div className="pad">
        <div className="stack" style={{ maxWidth: 1040 }}>
          {space.archivedAt && <ArchivedNotice space={space} />}
          <div>
            <h1 className="page-title">{t('spaceMembers.title', { name: space.name })}</h1>
            {members.data && <p className="page-intro">{i18n.tp('spaceMembers.intro', rows.length)}</p>}
          </div>
          {members.isError && <Callout tone="error" role="alert">{errorMessage(i18n, members.error)}</Callout>}
          <div className="set-box is-flush">
            <HorizontalScroll stickyFirstColumn>
              <table className="grid" style={{ minWidth: 680 }}>
                <thead>
                  <tr>
                    <th className="rn"><span className="sr-only">#</span></th>
                    <th className="primary"><span className="th"><Icon name="user" />{t('members.col.member')}</span></th>
                    <th><span className="th"><Icon name="mail" />{t('members.col.email')}</span></th>
                    <th><span className="th"><Icon name="tag" />{t('members.col.role')}</span></th>
                    <th><span className="th"><Icon name="clock" />{t('spaceMembers.col.added')}</span></th>
                    <th><span className="sr-only">{t('members.col.actions')}</span></th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((member, index) => (
                    <tr key={member.id} className={member.active ? undefined : 'is-disabled'}>
                      <td className="rn">{index + 1}</td>
                      <td className="primary">
                        <span className="who">
                          <Avatar name={member.displayName} seed={member.id} />
                          <span>
                            {member.displayName}
                            {member.self && <span className="muted"> {t('members.you')}</span>}
                            {!member.active && <> <span className="pill p-gray">{t('status.DISABLED')}</span></>}
                          </span>
                        </span>
                      </td>
                      <td style={{ maxWidth: 240 }} title={member.email}>{member.email}</td>
                      <td className="cell-menu" style={{ textAlign: 'left', width: 'auto' }}>
                        {manager ? (
                          <Menu
                            triggerClassName={`pill ${spaceRoleClass(member.role)}`}
                            label={t('spaceMembers.roleOf', { name: member.displayName })}
                            trigger={<>{t(`spaceRole.${member.role as SpaceRole}`)}<Icon name="chevron" /></>}
                          >
                            {SPACE_ROLES.map((role) => (
                              <MenuItem
                                key={role}
                                checked={member.role === role}
                                onSelect={() => member.role !== role && changeRole.mutate({ member, role })}
                              >
                                <span className={`pill ${spaceRoleClass(role)}`}>{t(`spaceRole.${role}`)}</span>
                              </MenuItem>
                            ))}
                          </Menu>
                        ) : (
                          <SpaceRolePill role={member.role} />
                        )}
                      </td>
                      <td>{i18n.date(member.addedAt)}</td>
                      <td className="cell-action">
                        {!space.archivedAt && (member.self || manager) && (
                          <button
                            type="button"
                            className="btn btn-ghost"
                            aria-label={member.self ? undefined : t('spaceMembers.removeOf', { name: member.displayName })}
                            onClick={() => { remove.reset(); setRemoving(member); }}
                          >
                            {t(member.self ? 'spaceMembers.leave' : 'spaceMembers.remove')}
                          </button>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </HorizontalScroll>
            {members.isPending && <div className="grid-empty" role="status"><Spinner /><span className="sr-only">{t('common.loading')}</span></div>}
          </div>

          <section aria-labelledby="space-roles">
            <h2 className="section-title" id="space-roles">{t('spaceMembers.roles')}</h2>
            <dl className="roles">
              {SPACE_ROLES.map((role) => (
                <div key={role}>
                  <dt><SpaceRolePill role={role} /></dt>
                  <dd>{t(`spaceRole.help.${role}`)}</dd>
                </div>
              ))}
            </dl>
            <p className="help" style={{ marginTop: 10 }}>{t('spaceMembers.adminsHelp')}</p>
          </section>
        </div>
      </div>

      {adding && <AddMembersDialog space={space} onClose={() => setAdding(false)} onAdded={refresh} />}
      {removing && (
        <ConfirmDialog
          title={removing.self
            ? t('spaceMembers.leaveTitle', { name: space.name })
            : t('spaceMembers.removeTitle', { name: removing.displayName })}
          body={removing.self
            ? t('spaceMembers.leaveBody')
            : t('spaceMembers.removeBody', { name: removing.displayName })}
          confirm={t(removing.self ? 'spaceMembers.leave' : 'spaceMembers.remove')}
          pending={remove.isPending}
          error={remove.error}
          onConfirm={() => remove.mutate(removing)}
          onClose={() => setRemoving(null)}
        />
      )}
    </View>
  );
}

/**
 * Adds members of the organization to the space, with the same role. Bringing new people into
 * the organization stays an invitation, created by an administrator.
 */
function AddMembersDialog({ space, onClose, onAdded }: { space: Space; onClose: () => void; onAdded: () => void }) {
  const i18n = useI18n();
  const { t } = i18n;
  const notify = useNotify();
  const [search, setSearch] = useState('');
  const [selected, setSelected] = useState<Map<string, Schemas['Candidate']>>(new Map());
  const [role, setRole] = useState<SpaceRole>('CONTRIBUTOR');
  const [refused, setRefused] = useState<string | null>(null);
  const q = useDebounced(search.trim(), 250);

  const candidates = useQuery({
    queryKey: keys.spaceCandidates(space.id, q),
    queryFn: () => call(() => api.GET('/api/v1/spaces/{spaceId}/members/candidates', {
      params: { path: { spaceId: space.id }, query: { q: q || undefined } },
    })),
  });

  // One request per person. Each one added leaves the selection: after a failure, trying again
  // only sends the people still to add.
  const unselect = (person: Schemas['Candidate']) => setSelected((current) => {
    const next = new Map(current);
    next.delete(person.id);
    return next;
  });

  const add = useMutation({
    mutationFn: async () => {
      setRefused(null);
      let added = 0;
      for (const person of [...selected.values()]) {
        try {
          await call(() => api.POST('/api/v1/spaces/{spaceId}/members', {
            params: { path: { spaceId: space.id } },
            body: { userId: person.id, role },
          }));
        } catch (error) {
          if (error instanceof ApiError && error.fieldErrors.some((problem) => problem.code === 'unknown_member')) {
            // Disabled or removed from the organization since the list was shown.
            unselect(person);
            setRefused(person.displayName);
            throw error;
          }
          if (!(error instanceof ApiError && error.code === 'already_space_member')) throw error;
        }
        added += 1;
        unselect(person);
      }
      return added;
    },
    onSuccess: (added) => {
      notify(i18n.tp('spaceMembers.added', added));
      onClose();
    },
    onSettled: () => {
      onAdded();
      void queryClient.invalidateQueries({ queryKey: keys.allSpaceCandidates(space.id) });
    },
  });

  const toggle = (person: Schemas['Candidate']) => {
    const next = new Map(selected);
    if (next.has(person.id)) next.delete(person.id);
    else next.set(person.id, person);
    setSelected(next);
  };

  const submit = (event: FormEvent) => {
    event.preventDefault();
    if (selected.size > 0) add.mutate();
  };

  // The people chosen stay visible while another search is typed.
  const shown = [...selected.values(), ...(candidates.data ?? []).filter((person) => !selected.has(person.id))];
  return (
    <Modal
      title={t('spaceMembers.add')}
      onClose={onClose}
      onSubmit={submit}
      footer={(
        <>
          <button type="button" className="btn" onClick={onClose}>{t('common.cancel')}</button>
          <button type="submit" className="btn btn-primary" disabled={add.isPending || selected.size === 0}>
            {add.isPending ? <Spinner /> : <Icon name="plus" />}
            {selected.size > 0 ? i18n.tp('addMembers.submit', selected.size) : t('addMembers.submitNone')}
          </button>
        </>
      )}
    >
      {add.isError && (
        <Callout tone="error" role="alert">
          {refused ? t('addMembers.refused', { name: refused }) : errorMessage(i18n, add.error)}
        </Callout>
      )}
      {candidates.isError && <Callout tone="error" role="alert">{errorMessage(i18n, candidates.error)}</Callout>}
      <div className="search-box">
        <Icon name="search" />
        <input
          type="search"
          placeholder={t('addMembers.search')}
          aria-label={t('addMembers.search')}
          value={search}
          data-autofocus
          onChange={(event) => setSearch(event.target.value)}
        />
        {candidates.isFetching && <Spinner />}
      </div>
      {shown.length > 0 ? (
        <div className="people-pick" role="group" aria-label={t('addMembers.people')}>
          {shown.map((person) => (
            <label key={person.id}>
              <input type="checkbox" checked={selected.has(person.id)} onChange={() => toggle(person)} />
              <Avatar name={person.displayName} seed={person.id} />
              <span className="list-main">
                <span className="list-title">{person.displayName}</span>
                <span className="list-sub">{person.email}</span>
              </span>
            </label>
          ))}
        </div>
      ) : candidates.isSuccess && (
        <p className="help">{q ? t('addMembers.noResult', { q }) : t('addMembers.none')}</p>
      )}
      <SelectField label={t('addMembers.role')} value={role} onChange={(event) => setRole(event.target.value as SpaceRole)}>
        {SPACE_ROLES.map((value) => <option key={value} value={value}>{t(`spaceRole.${value}`)}</option>)}
      </SelectField>
      <p className="help">{t('addMembers.help')}</p>
    </Modal>
  );
}
