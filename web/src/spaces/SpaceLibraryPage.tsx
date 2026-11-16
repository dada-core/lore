import { useMutation, useQuery } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { Link } from 'react-router';
import { api, call, type Schemas } from '../api/client';
import { keys, queryClient } from '../api/queryClient';
import { errorMessage } from '../i18n/errors';
import { useI18n } from '../i18n/i18n';
import { View } from '../layouts/AppShell';
import { ConfirmDialog } from '../pages/admin/MemberDialogs';
import { useDocumentTitle } from '../ui/bits';
import { Callout, checks, hasProblems, message, problemsOf, TextField, validate, type Problems } from '../ui/forms';
import { Icon, Spinner } from '../ui/Icon';
import { Menu, MenuItem } from '../ui/Menu';
import { Modal } from '../ui/Modal';
import { useNotify } from '../ui/Toast';
import { canContribute, canManage, refreshSpaces, SpaceIcon, useSpace, type Space } from './spaces';
import { spaceCrumbs } from './SpaceRoute';

type Folder = Schemas['FolderView'];
type Dialog = { kind: 'create' } | { kind: 'rename'; folder: Folder } | { kind: 'delete'; folder: Folder };

/**
 * The library of a space: its folders for now; the documents arrive with the import. An empty
 * space says where to start.
 */
export function SpaceLibraryPage() {
  const i18n = useI18n();
  const { t } = i18n;
  const notify = useNotify();
  const space = useSpace();
  const [dialog, setDialog] = useState<Dialog | null>(null);
  const contributor = canContribute(space);
  useDocumentTitle(space.name);

  const folders = useQuery({
    queryKey: keys.folders(space.id),
    queryFn: () => call(() => api.GET('/api/v1/spaces/{spaceId}/folders', { params: { path: { spaceId: space.id } } })),
  });

  const remove = useMutation({
    mutationFn: (folder: Folder) => call(() => api.DELETE('/api/v1/spaces/{spaceId}/folders/{folderId}', {
      params: { path: { spaceId: space.id, folderId: folder.id } },
    })),
    onSuccess: () => {
      setDialog(null);
      notify(t('folder.deleted'));
    },
    onSettled: () => {
      void queryClient.invalidateQueries({ queryKey: keys.folders(space.id) });
      void refreshSpaces();
    },
  });

  const list = folders.data ?? [];
  const newFolder = contributor && (
    <button type="button" className="btn" onClick={() => setDialog({ kind: 'create' })}>
      <Icon name="folder-plus" />
      <span className="btn-label">{t('library.newFolder')}</span>
    </button>
  );

  return (
    <View crumbs={spaceCrumbs(t('nav.spaces'), space)} actions={newFolder || undefined}>
      <div className="pad">
        {space.archivedAt && <ArchivedNotice space={space} />}
        {folders.isError && <Callout tone="error" role="alert">{errorMessage(i18n, folders.error)}</Callout>}
        {folders.isPending && <div className="grid-empty" role="status"><Spinner /><span className="sr-only">{t('common.loading')}</span></div>}
        {folders.isSuccess && list.length === 0 && (
          <div className="empty">
            <SpaceIcon space={space} large />
            <h1 className="page-title">{t('library.empty', { name: space.name })}</h1>
            <p>{t('library.emptyBody')}</p>
            <div className="actions">
              {contributor && (
                <button type="button" className="btn btn-primary btn-lg" onClick={() => setDialog({ kind: 'create' })}>
                  <Icon name="folder-plus" />{t('library.newFolder')}
                </button>
              )}
              {canManage(space) && !space.archivedAt && (
                <Link className="btn btn-lg" to={`/spaces/${space.slug}/members`}><Icon name="users" />{t('library.addMembers')}</Link>
              )}
            </div>
            {contributor && <p className="help">{t('library.tip')}</p>}
          </div>
        )}
        {list.length > 0 && (
          <div className="stack" style={{ maxWidth: 880 }}>
            <div>
              <h1 className="page-title space-title"><SpaceIcon space={space} large />{space.name}</h1>
              {space.description && <p className="page-intro">{space.description}</p>}
            </div>
            <section aria-labelledby="space-folders">
              <h2 className="section-title" id="space-folders">{t('library.folders')} <span className="muted">{list.length}</span></h2>
              <ul className="list">
                {list.map((folder) => (
                  <li key={folder.id}>
                    <div className="list-row">
                      <span className="cat-ico"><Icon name="folder" /></span>
                      <span className="list-main">
                        <span className="list-title">{folder.name}</span>
                        <span className="list-sub">{t('folder.createdOn', { date: i18n.date(folder.createdAt) })}</span>
                      </span>
                      {contributor && (
                        <Menu align="end" label={t('folder.actionsFor', { name: folder.name })} trigger={<Icon name="dots" />}>
                          <MenuItem icon="pencil" onSelect={() => setDialog({ kind: 'rename', folder })}>{t('folder.rename')}</MenuItem>
                          <MenuItem icon="trash" danger onSelect={() => { remove.reset(); setDialog({ kind: 'delete', folder }); }}>
                            {t('folder.delete')}
                          </MenuItem>
                        </Menu>
                      )}
                    </div>
                  </li>
                ))}
              </ul>
            </section>
          </div>
        )}
      </div>

      {(dialog?.kind === 'create' || dialog?.kind === 'rename') && (
        <FolderDialog space={space} folder={dialog.kind === 'rename' ? dialog.folder : null} onClose={() => setDialog(null)} />
      )}
      {dialog?.kind === 'delete' && (
        <ConfirmDialog
          title={t('folder.deleteTitle', { name: dialog.folder.name })}
          body={t('folder.deleteBody', { name: dialog.folder.name })}
          confirm={t('folder.delete')}
          pending={remove.isPending}
          error={remove.error}
          onConfirm={() => remove.mutate(dialog.folder)}
          onClose={() => setDialog(null)}
        />
      )}
    </View>
  );
}

/** Shown at the top of every page of an archived space. */
export function ArchivedNotice({ space }: { space: Space }) {
  const { t } = useI18n();
  return (
    <div style={{ marginBottom: 16 }}>
      <Callout tone="warn">
        {t('library.archived')}
        {canManage(space) && <> <Link className="link" to={`/spaces/${space.slug}/settings`}>{t('library.toSettings')}</Link></>}
      </Callout>
    </div>
  );
}

/** "New folder", or the renaming of an existing one. */
function FolderDialog({ space, folder, onClose }: { space: Space; folder: Folder | null; onClose: () => void }) {
  const i18n = useI18n();
  const { t } = i18n;
  const notify = useNotify();
  const [name, setName] = useState(folder?.name ?? '');
  const [problems, setProblems] = useState<Problems>({});

  const save = useMutation({
    mutationFn: () => folder
      ? call(() => api.PATCH('/api/v1/spaces/{spaceId}/folders/{folderId}', {
        params: { path: { spaceId: space.id, folderId: folder.id } },
        body: { name: name.trim() },
      }))
      : call(() => api.POST('/api/v1/spaces/{spaceId}/folders', { params: { path: { spaceId: space.id } }, body: { name: name.trim() } })),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: keys.folders(space.id) });
      void refreshSpaces();
      notify(t(folder ? 'folder.renamed' : 'folder.created'));
      onClose();
    },
    onError: (error) => setProblems(problemsOf(error)),
  });

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const found = validate({ name: checks.required(name, 80) });
    setProblems(found);
    if (!hasProblems(found)) save.mutate();
  };

  return (
    <Modal
      title={t(folder ? 'folder.renameTitle' : 'library.newFolder')}
      onClose={onClose}
      onSubmit={submit}
      footer={(
        <>
          <button type="button" className="btn" onClick={onClose}>{t('common.cancel')}</button>
          <button type="submit" className="btn btn-primary" disabled={save.isPending}>
            {save.isPending && <Spinner />}
            {t(folder ? 'folder.rename' : 'folder.create')}
          </button>
        </>
      )}
    >
      {save.isError && !hasProblems(problemsOf(save.error)) && (
        <Callout tone="error" role="alert">{errorMessage(i18n, save.error)}</Callout>
      )}
      <TextField
        label={t('folder.name')}
        placeholder={t('folder.placeholder')}
        value={name}
        data-autofocus
        onChange={(event) => setName(event.target.value)}
        help={t('folder.in', { name: space.name })}
        error={message(i18n, problems.name)}
      />
    </Modal>
  );
}
