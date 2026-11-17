import { useMutation, useQuery } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { api, ApiError, call } from '../api/client';
import { keys, queryClient } from '../api/queryClient';
import { errorMessage } from '../i18n/errors';
import { useI18n } from '../i18n/i18n';
import { Avatar } from '../ui/bits';
import { Callout, hasProblems, message, problemsOf, SelectField, TextAreaField, validate, type Problems } from '../ui/forms';
import { Icon, Spinner } from '../ui/Icon';
import { Modal } from '../ui/Modal';
import { useNotify } from '../ui/Toast';
import { useUser } from '../session/session';
import { refreshDocuments, type Document } from './documents';

const MAX_DESCRIPTION = 2000;

/** Keeps the document the server sent back, and loads again what shows it elsewhere. */
function saved(document: Document): void {
  queryClient.setQueryData(keys.document(document.id), document);
  void refreshDocuments(document.space.id);
}

/**
 * "Document access": the whole space, or some people only. The maintainers of the space and the
 * owner of the document always see it, ticked and locked.
 */
export function AccessDialog({ document, onClose }: { document: Document; onClose: () => void }) {
  const i18n = useI18n();
  const { t } = i18n;
  const notify = useNotify();
  const spaceId = document.space.id;
  const access = useQuery({
    queryKey: keys.documentAccess(document.id),
    queryFn: () => call(() => api.GET('/api/v1/documents/{id}/access', { params: { path: { id: document.id } } })),
  });
  const members = useQuery({
    queryKey: keys.spaceMembers(spaceId),
    queryFn: () => call(() => api.GET('/api/v1/spaces/{spaceId}/members', { params: { path: { spaceId } } })),
  });
  const [restricted, setRestricted] = useState<boolean | null>(null);
  const [picked, setPicked] = useState<Set<string> | null>(null);
  const isRestricted = restricted ?? access.data?.restricted ?? document.restricted;
  const chosen = picked ?? new Set((access.data?.people ?? []).filter((person) => person.reasons.includes('chosen')).map((person) => person.id));

  const save = useMutation({
    mutationFn: () => {
      // Only people still members of the space: someone removed meanwhile is left out.
      const current = new Set((members.data ?? []).map((member) => member.id));
      const userIds = isRestricted ? [...chosen].filter((id) => current.has(id)) : [];
      return call(() => api.PUT('/api/v1/documents/{id}/access', {
        params: { path: { id: document.id } },
        body: { restricted: isRestricted, userIds },
      }));
    },
    onError: (error) => {
      // Someone chosen left the space meanwhile: the members and the access are asked again.
      if (error instanceof ApiError && error.fieldErrors.some((problem) => problem.field === 'userIds')) {
        void queryClient.invalidateQueries({ queryKey: keys.spaceMembers(spaceId) });
        void queryClient.invalidateQueries({ queryKey: keys.documentAccess(document.id) });
      }
    },
    onSuccess: (view) => {
      queryClient.setQueryData(keys.documentAccess(document.id), view);
      void queryClient.invalidateQueries({ queryKey: keys.document(document.id) });
      void refreshDocuments(spaceId);
      notify(t('access.saved'));
      onClose();
    },
  });

  const toggle = (id: string) => {
    const next = new Set(chosen);
    if (next.has(id)) next.delete(id);
    else next.add(id);
    setPicked(next);
  };

  const submit = (event: FormEvent) => {
    event.preventDefault();
    save.mutate();
  };

  const count = access.data?.spaceMembers ?? members.data?.length ?? 0;
  const fieldError = save.error ? problemsOf(save.error).userIds : undefined;
  return (
    <Modal
      title={t('access.title')}
      onClose={onClose}
      onSubmit={submit}
      footer={(
        <>
          <button type="button" className="btn" onClick={onClose}>{t('common.cancel')}</button>
          <button type="submit" className="btn btn-primary" disabled={save.isPending || !access.data || !members.data}>
            {save.isPending && <Spinner />}
            {t('common.save')}
          </button>
        </>
      )}
    >
      <p className="help" style={{ marginTop: -4 }}>{document.title}</p>
      {save.isError && <Callout tone="error" role="alert">{fieldError ? message(i18n, fieldError) : errorMessage(i18n, save.error)}</Callout>}
      {(access.error ?? members.error) && <Callout tone="error" role="alert">{errorMessage(i18n, access.error ?? members.error)}</Callout>}
      <label className="radio-card">
        <input type="radio" name="access" checked={!isRestricted} onChange={() => setRestricted(false)} data-autofocus={isRestricted ? undefined : true} />
        <span>
          <strong><Icon name="users" />{t('access.all')}</strong>
          <span className="help">{i18n.tp('access.allHelp', count)}</span>
        </span>
      </label>
      <label className="radio-card">
        <input type="radio" name="access" checked={isRestricted} onChange={() => setRestricted(true)} data-autofocus={isRestricted ? true : undefined} />
        <span>
          <strong><Icon name="lock" />{t('access.some')}</strong>
          <span className="help">{t('access.someHelp')}</span>
        </span>
      </label>
      <fieldset className="plain" disabled={!isRestricted}>
        <legend className="sr-only">{t('access.people')}</legend>
        {members.isPending && <div role="status"><Spinner /><span className="sr-only">{t('common.loading')}</span></div>}
        {members.data && (
          <div className="people-pick">
            {members.data.map((member) => {
              const maintainer = member.role === 'MAINTAINER';
              const owner = document.owner?.id === member.id;
              const always = maintainer || owner;
              return (
                <label key={member.id}>
                  <input type="checkbox" checked={always || chosen.has(member.id)} disabled={always} onChange={() => toggle(member.id)} />
                  <Avatar name={member.displayName} seed={member.id} />
                  <span className="list-main">
                    <span className="list-title">{member.displayName}</span>
                    <span className="list-sub">{member.email}</span>
                  </span>
                  {owner
                    ? <span className="note">{t('access.reason.owner')}</span>
                    : maintainer && <span className="note">{t('access.reason.maintainer')}</span>}
                </label>
              );
            })}
          </div>
        )}
      </fieldset>
      <p className="help">{t('access.always')}</p>
    </Modal>
  );
}

/** "Move the document": to another folder of its space, or to its root. */
export function MoveDialog({ document, onClose }: { document: Document; onClose: () => void }) {
  const i18n = useI18n();
  const { t } = i18n;
  const notify = useNotify();
  const spaceId = document.space.id;
  const [folderId, setFolderId] = useState(document.folder?.id ?? '');
  const folders = useQuery({
    queryKey: keys.folders(spaceId),
    queryFn: () => call(() => api.GET('/api/v1/spaces/{spaceId}/folders', { params: { path: { spaceId } } })),
  });

  const move = useMutation({
    mutationFn: () => call(() => api.POST('/api/v1/documents/{id}/move', {
      params: { path: { id: document.id } },
      body: { folderId: folderId || null },
    })),
    onSuccess: (moved) => {
      saved(moved);
      notify(t('document.moved'));
      onClose();
    },
  });

  const submit = (event: FormEvent) => {
    event.preventDefault();
    if (folderId === (document.folder?.id ?? '')) onClose();
    else move.mutate();
  };

  const problem = move.error ? problemsOf(move.error).folderId : undefined;
  return (
    <Modal
      title={t('document.moveTitle')}
      onClose={onClose}
      onSubmit={submit}
      footer={(
        <>
          <button type="button" className="btn" onClick={onClose}>{t('common.cancel')}</button>
          <button type="submit" className="btn btn-primary" disabled={move.isPending || !folders.data}>
            {move.isPending ? <Spinner /> : <Icon name="folder" />}
            {t('document.moveSubmit')}
          </button>
        </>
      )}
    >
      <p className="help" style={{ marginTop: -4 }}>{document.title}</p>
      {move.isError && !problem && <Callout tone="error" role="alert">{errorMessage(i18n, move.error)}</Callout>}
      {folders.isError && <Callout tone="error" role="alert">{errorMessage(i18n, folders.error)}</Callout>}
      <SelectField
        label={t('document.moveTo')}
        value={folderId}
        data-autofocus
        onChange={(event) => setFolderId(event.target.value)}
        help={t('folder.in', { name: document.space.name })}
        error={message(i18n, problem)}
      >
        <option value="">{t('library.noFolder')}</option>
        {(folders.data ?? []).map((folder) => <option key={folder.id} value={folder.id}>{folder.name}</option>)}
      </SelectField>
    </Modal>
  );
}

/** "Add a description": what a scanned document is found by. */
export function DescriptionDialog({ document, onClose }: { document: Document; onClose: () => void }) {
  const i18n = useI18n();
  const { t } = i18n;
  const notify = useNotify();
  const [description, setDescription] = useState(document.description ?? '');
  const [problems, setProblems] = useState<Problems>({});

  const save = useMutation({
    mutationFn: () => call(() => api.PATCH('/api/v1/documents/{id}', {
      params: { path: { id: document.id } },
      // Empty, the description is removed.
      body: { description: description.trim() },
    })),
    onSuccess: (changed) => {
      saved(changed);
      notify(t('document.descriptionSaved'));
      onClose();
    },
    onError: (error) => setProblems(problemsOf(error)),
  });

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const found = validate({
      description: description.trim().length > MAX_DESCRIPTION ? { code: 'too_long', params: { max: MAX_DESCRIPTION } } : null,
    });
    setProblems(found);
    if (!hasProblems(found)) save.mutate();
  };

  return (
    <Modal
      title={t(document.description ? 'document.editDescription' : 'document.addDescription')}
      onClose={onClose}
      onSubmit={submit}
      footer={(
        <>
          <button type="button" className="btn" onClick={onClose}>{t('common.cancel')}</button>
          <button type="submit" className="btn btn-primary" disabled={save.isPending}>
            {save.isPending && <Spinner />}
            {t('common.save')}
          </button>
        </>
      )}
    >
      <p className="help" style={{ marginTop: -4 }}>{document.title}</p>
      {save.isError && !hasProblems(problemsOf(save.error)) && <Callout tone="error" role="alert">{errorMessage(i18n, save.error)}</Callout>}
      <TextAreaField
        label={t('document.description')}
        value={description}
        data-autofocus
        rows={6}
        onChange={(event) => setDescription(event.target.value)}
        help={t('document.descriptionHelp')}
        error={message(i18n, problems.description)}
      />
    </Modal>
  );
}

/**
 * "Change the owner": a member of the space with at least the Contributor role. For a restricted
 * document, the window says that the new owner will see it, and that the person handing it over
 * will not any more, unless they are chosen or a maintainer.
 */
export function OwnerDialog({ document, onClose, onHandedOver }: {
  document: Document;
  onClose: () => void;
  /** The person no longer sees the document: the page is left. */
  onHandedOver: (name: string) => void;
}) {
  const i18n = useI18n();
  const { t } = i18n;
  const notify = useNotify();
  const user = useUser();
  const spaceId = document.space.id;
  const [search, setSearch] = useState('');
  const [picked, setPicked] = useState<string | null>(null);
  const members = useQuery({
    queryKey: keys.spaceMembers(spaceId),
    queryFn: () => call(() => api.GET('/api/v1/spaces/{spaceId}/members', { params: { path: { spaceId } } })),
  });
  const access = useQuery({
    queryKey: keys.documentAccess(document.id),
    queryFn: () => call(() => api.GET('/api/v1/documents/{id}/access', { params: { path: { id: document.id } } })),
    enabled: document.restricted,
  });
  const candidates = (members.data ?? []).filter((member) => member.active && member.id !== document.owner?.id
    && (member.role === 'CONTRIBUTOR' || member.role === 'MAINTAINER'));
  const folded = (text: string) => text.normalize('NFD').replace(/\p{M}/gu, '').toLowerCase();
  const shown = candidates.filter((member) => folded(`${member.displayName} ${member.email}`).includes(folded(search.trim())));
  const chosen = candidates.find((member) => member.id === picked);
  const me = access.data?.people.find((person) => person.id === user.id);
  // Said only once the people who see it are known.
  const loses = !user.isAdmin && access.isSuccess
    && !(me?.reasons.some((reason) => reason === 'chosen' || reason === 'maintainer') ?? false);

  const save = useMutation({
    mutationFn: () => call(() => api.PUT('/api/v1/documents/{id}/owner', {
      params: { path: { id: document.id } },
      body: { userId: picked },
    })),
    onSuccess: (changed) => {
      const name = changed.owner?.displayName ?? '';
      void refreshDocuments(spaceId);
      void queryClient.invalidateQueries({ queryKey: keys.documentAccess(document.id) });
      if (!stillSeen(changed)) {
        onHandedOver(name);
        return;
      }
      queryClient.setQueryData(keys.document(document.id), changed);
      notify(t('owner.changed', { name }));
      onClose();
    },
  });

  return (
    <Modal
      title={t('owner.title')}
      onClose={onClose}
      onSubmit={(event) => {
        event.preventDefault();
        if (picked) save.mutate();
      }}
      footer={(
        <>
          <button type="button" className="btn" onClick={onClose}>{t('common.cancel')}</button>
          <button type="submit" className="btn btn-primary" disabled={!picked || save.isPending}>
            {save.isPending && <Spinner />}
            {t('owner.submit')}
          </button>
        </>
      )}
    >
      <p className="help" style={{ marginTop: -4 }}>{t('owner.intro', { title: document.title })}</p>
      {save.isError && (
        <Callout tone="error" role="alert">
          {problemsOf(save.error).userId ? message(i18n, problemsOf(save.error).userId) : errorMessage(i18n, save.error)}
        </Callout>
      )}
      {members.error && <Callout tone="error" role="alert">{errorMessage(i18n, members.error)}</Callout>}
      {members.isPending && <div role="status"><Spinner /><span className="sr-only">{t('common.loading')}</span></div>}
      {members.data && candidates.length === 0 && <p className="muted">{t('owner.none')}</p>}
      {candidates.length > 0 && (
        <>
          <div className="field">
            <label className="sr-only" htmlFor="owner-search">{t('owner.search')}</label>
            <input
              id="owner-search"
              className="input"
              type="search"
              placeholder={t('owner.search')}
              value={search}
              onChange={(event) => setSearch(event.target.value)}
              data-autofocus
            />
          </div>
          <fieldset className="plain">
            <legend className="sr-only">{t('owner.title')}</legend>
            <div className="people-pick">
              {shown.map((member) => (
                <label key={member.id}>
                  <input type="radio" name="owner" checked={picked === member.id} onChange={() => setPicked(member.id)} />
                  <Avatar name={member.displayName} seed={member.id} />
                  <span className="list-main">
                    <span className="list-title">{member.displayName}</span>
                    <span className="list-sub">{member.email}</span>
                  </span>
                </label>
              ))}
              {shown.length === 0 && <p className="muted">{t('owner.noMatch')}</p>}
            </div>
          </fieldset>
        </>
      )}
      {document.restricted && chosen && (
        <Callout tone="warn">
          {t('owner.sees', { name: chosen.displayName })}
          {loses && <> {t('owner.loses')}</>}
        </Callout>
      )}
    </Modal>
  );
}

/** Whether the answer still shows something the person may do: they keep seeing the document. */
function stillSeen(document: Document): boolean {
  return Object.values(document.actions).some(Boolean);
}

/**
 * A link to copy when the browser refuses the clipboard (Lore served over http:// on an internal
 * network): shown already selected.
 */
export function LinkDialog({ title, link, onClose }: { title: string; link: string; onClose: () => void }) {
  const { t } = useI18n();
  return (
    <Modal
      title={title}
      onClose={onClose}
      footer={<button type="button" className="btn" onClick={onClose}>{t('common.close')}</button>}
    >
      <div className="field">
        <label className="sr-only" htmlFor="copy-link">{title}</label>
        <input
          id="copy-link"
          className="input input-mono"
          readOnly
          value={link}
          data-autofocus
          onFocus={(event) => event.currentTarget.select()}
        />
        <p className="help">{t('reader.linkHelp')}</p>
      </div>
    </Modal>
  );
}
