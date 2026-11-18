import { useInfiniteQuery, useMutation, useQuery } from '@tanstack/react-query';
import { Fragment, useState, type FormEvent } from 'react';
import { Link, useSearchParams } from 'react-router';
import { api, call, type Schemas } from '../api/client';
import { keys, queryClient } from '../api/queryClient';
import {
  CategoryPill,
  FormatIcon,
  formatName,
  IndexPill,
  languageName,
  Owner,
  refreshAllDocuments,
  StatusLabel,
  useOpenImport,
  whileExtracting,
  type DocumentItem,
} from '../documents/documents';
import { errorMessage } from '../i18n/errors';
import { useI18n } from '../i18n/i18n';
import { View } from '../layouts/AppShell';
import { ConfirmDialog } from '../pages/admin/MemberDialogs';
import { HorizontalScroll, useDocumentTitle } from '../ui/bits';
import { Callout, checks, hasProblems, message, problemsOf, TextField, validate, type Problems } from '../ui/forms';
import { Icon, Spinner } from '../ui/Icon';
import { Menu, MenuItem, MenuSeparator } from '../ui/Menu';
import { Modal } from '../ui/Modal';
import { LIBRARY_COLUMNS, LIBRARY_SORTS, useLibraryView, type LibraryColumn } from '../ui/preferences';
import { useNotify } from '../ui/Toast';
import { LibraryBoard } from './LibraryBoard';
import { canContribute, canManage, refreshSpaces, SpaceIcon, useSpace, type Space } from './spaces';
import { spaceCrumbs } from './SpaceRoute';

type Folder = Schemas['FolderView'];
type Dialog =
  | { kind: 'create' }
  | { kind: 'rename'; folder: Folder }
  | { kind: 'delete'; folder: Folder }
  | { kind: 'notEmpty'; folder: Folder; count: number };

/** Documents loaded per page; the server gives 200 at most. */
const PAGE = 200;
/** The group of the documents at the root of the space, and the value of the filter for it. */
const ROOT = 'none';

/**
 * The library of a space: its documents in a grid, grouped by folder, the root of the space
 * included; a filter by folder, with the counts; or a board by status ({@code ?view=kanban}).
 * Folders are renamed and deleted from the head of their group. Archived documents are left out
 * of the grid, but for who sees them and asks ({@code ?status=ARCHIVED}). While a text is being
 * extracted, the grid asks again every ten seconds.
 */
export function SpaceLibraryPage() {
  const i18n = useI18n();
  const { t } = i18n;
  const notify = useNotify();
  const space = useSpace();
  const openImport = useOpenImport();
  const [params, setParams] = useSearchParams();
  const [dialog, setDialog] = useState<Dialog | null>(null);
  const [folded, setFolded] = useState<Set<string>>(() => new Set());
  const contributor = canContribute(space);
  const filter = params.get('folder');
  const board = params.get('view') === 'kanban';
  const onlyArchived = !board && params.get('status') === 'ARCHIVED';
  const [view, setView] = useLibraryView();
  const direction = view.direction ?? (view.sort === 'updated' ? 'desc' : 'asc');
  const hidden = new Set<LibraryColumn>(view.hidden);
  const columns = 2 + LIBRARY_COLUMNS.filter((column) => !hidden.has(column)).length;
  useDocumentTitle(space.name);

  const folders = useQuery({
    queryKey: keys.folders(space.id),
    queryFn: () => call(() => api.GET('/api/v1/spaces/{spaceId}/folders', { params: { path: { spaceId: space.id } } })),
  });
  const summary = useQuery({
    queryKey: keys.documentSummary(space.id),
    queryFn: () => call(() => api.GET('/api/v1/spaces/{spaceId}/documents/summary', { params: { path: { spaceId: space.id } } })),
    refetchInterval: whileExtracting((data) => data.inProgress > 0),
  });
  const documents = useInfiniteQuery({
    queryKey: [...keys.documents(space.id, filter ?? 'all'), view.sort, direction, onlyArchived],
    queryFn: ({ pageParam }) => call(() => api.GET('/api/v1/spaces/{spaceId}/documents', {
      params: {
        path: { spaceId: space.id },
        query: {
          folder: filter ?? undefined,
          status: onlyArchived ? ['ARCHIVED'] : undefined,
          sort: view.sort,
          direction,
          page: pageParam,
          size: PAGE,
        },
      },
    })),
    enabled: !board,
    initialPageParam: 0,
    // Another folder shown: the grid stays in place until it arrives, never with another space's documents,
    // nor the archived ones under the title of the others.
    placeholderData: (previous, previousQuery) => (previousQuery?.queryKey[2] === space.id
      && previousQuery.queryKey.at(-1) === onlyArchived ? previous : undefined),
    getNextPageParam: (last) => ((last.page + 1) * last.size < last.total ? last.page + 1 : undefined),
    refetchInterval: whileExtracting((data) => data.pages.some((page) => page.items.some((item) => item.extractionStatus === 'PENDING'
      || item.newerVersion?.extractionStatus === 'PENDING'))),
  });

  const remove = useMutation({
    mutationFn: (folder: Folder) => call(() => api.DELETE('/api/v1/spaces/{spaceId}/folders/{folderId}', {
      params: { path: { spaceId: space.id, folderId: folder.id } },
    })),
    onSuccess: (_, folder) => {
      setDialog(null);
      notify(t('folder.deleted'));
      if (filter === folder.id) setParams({}, { replace: true });
    },
    onSettled: () => {
      void queryClient.invalidateQueries({ queryKey: keys.folders(space.id) });
      void refreshSpaces();
      void refreshAllDocuments();
    },
  });

  const folderList = folders.data ?? [];
  const items = documents.data?.pages.flatMap((page) => page.items) ?? [];
  const total = documents.data?.pages[0]?.total ?? 0;
  const counts = new Map((summary.data?.folders ?? []).map((entry) => [entry.folderId ?? ROOT, entry.count]));
  const filtered = filter === ROOT ? null : folderList.find((folder) => folder.id === filter);
  const empty = !filter && !onlyArchived && folders.isSuccess && summary.isSuccess && folderList.length === 0
    && summary.data.total === 0 && (summary.data.archived ?? 0) === 0;
  const loading = folders.isPending || summary.isPending || (!board && documents.isPending);
  const archivedCount = summary.data?.archived ?? 0;
  // The address of a view, the folder kept.
  const viewAddress = (kanban: boolean) => {
    const next = new URLSearchParams();
    if (filter) next.set('folder', filter);
    if (kanban) next.set('view', 'kanban');
    const query = next.toString();
    return `/spaces/${space.slug}${query ? `?${query}` : ''}`;
  };
  const error = folders.error ?? summary.error ?? documents.error;

  const askDelete = (folder: Folder) => {
    const count = counts.get(folder.id) ?? 0;
    remove.reset();
    setDialog(count > 0 ? { kind: 'notEmpty', folder, count } : { kind: 'delete', folder });
  };
  const toggleGroup = (key: string) => setFolded((current) => {
    const next = new Set(current);
    if (next.has(key)) next.delete(key);
    else next.add(key);
    return next;
  });
  const showFolder = (value: string | null) => {
    const next: Record<string, string> = value ? { folder: value } : {};
    if (board) next.view = 'kanban';
    if (onlyArchived) next.status = 'ARCHIVED';
    setParams(next);
  };

  // The groups shown: the root of the space when it holds documents, then every folder, by name.
  const groups: { key: string; folder: Folder | null; documents: DocumentItem[] }[] = [];
  if (!filter || filter === ROOT) {
    const root = items.filter((item) => !item.folderId);
    if (root.length > 0 || filter === ROOT) groups.push({ key: ROOT, folder: null, documents: root });
  }
  for (const folder of folderList) {
    if (filter && filter !== folder.id) continue;
    groups.push({ key: folder.id, folder, documents: items.filter((item) => item.folderId === folder.id) });
  }

  const actions = contributor ? (
    <>
      <button type="button" className="btn" onClick={() => setDialog({ kind: 'create' })}>
        <Icon name="folder-plus" />
        <span className="btn-label">{t('library.newFolder')}</span>
      </button>
      <button type="button" className="btn btn-primary" onClick={openImport} title={`${t('library.import')} (I)`}>
        <Icon name="upload" />
        <span className="btn-label">{t('import.open')}</span>
      </button>
    </>
  ) : undefined;

  // The foot of the grid: counts of the whole space, or of the folder shown.
  const shown = filter || onlyArchived ? items : null;
  const foot = {
    total: filter || onlyArchived ? total : summary.data?.total ?? 0,
    inProgress: shown ? shown.filter((item) => item.extractionStatus === 'PENDING').length : summary.data?.inProgress ?? 0,
    scanned: shown ? shown.filter((item) => item.extractionStatus === 'SCANNED').length : summary.data?.scanned ?? 0,
    failed: shown ? shown.filter((item) => item.extractionStatus === 'FAILED').length : summary.data?.failed ?? 0,
  };
  // The lines are numbered across the groups that are unfolded.
  const numbers = new Map<string, number>();
  for (const group of groups) {
    if (folded.has(group.key)) continue;
    for (const item of group.documents) numbers.set(item.id, numbers.size + 1);
  }

  return (
    <View crumbs={spaceCrumbs(t('nav.spaces'), space)} actions={actions}>
      <div className="pad">
        {space.archivedAt && <ArchivedNotice space={space} />}
        {error && <Callout tone="error" role="alert">{errorMessage(i18n, error)}</Callout>}
        {loading && !error && <div className="grid-empty" role="status"><Spinner /><span className="sr-only">{t('common.loading')}</span></div>}
        {empty && (
          <div className="empty">
            <SpaceIcon space={space} large />
            <h1 className="page-title">{t('library.empty', { name: space.name })}</h1>
            <p>{t(contributor ? 'library.emptyBody' : 'library.emptyReader')}</p>
            <div className="actions">
              {contributor && (
                <button type="button" className="btn btn-primary btn-lg" onClick={openImport}>
                  <Icon name="upload" />{t('library.import')}
                </button>
              )}
              {contributor && (
                <button type="button" className="btn btn-lg" onClick={() => setDialog({ kind: 'create' })}>
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
        {!empty && !loading && !error && (
          <div className="stack">
            <div>
              <h1 className="page-title space-title"><SpaceIcon space={space} large />{space.name}</h1>
              {space.description && <p className="page-intro">{space.description}</p>}
            </div>
            <div>
              <nav className="lib-tools" aria-label={t('library.views')} style={{ marginBottom: 6 }}>
                <Link className="view-tab" to={viewAddress(false)} aria-current={!board ? 'page' : undefined}>
                  <Icon name="grid" />{t('library.view.grid')}
                </Link>
                <Link className="view-tab" to={viewAddress(true)} aria-current={board ? 'page' : undefined}>
                  <Icon name="columns" />{t('library.view.kanban')}
                </Link>
              </nav>
              {onlyArchived && (
                <div className="row-between" style={{ marginBottom: 10 }}>
                  <h2 className="section-title">{t('library.archivedTitle')}</h2>
                  <Link className="link" to={viewAddress(false)}>{t('library.allDocuments')}</Link>
                </div>
              )}
              <div className="lib-tools" style={{ marginBottom: 10 }}>
                <Menu
                  triggerClassName={filter ? 'tool is-on-blue' : 'tool'}
                  label={t('library.filterBy')}
                  trigger={(
                    <>
                      <Icon name="filter" />
                      {filter === ROOT
                        ? t('library.filterFolder', { name: t('library.noFolder') })
                        : filtered ? t('library.filterFolder', { name: filtered.name }) : t('library.filter')}
                    </>
                  )}
                >
                  <MenuItem icon="file-text" checked={!filter} onSelect={() => showFolder(null)}>
                    {t('library.allDocuments')}<span className="kbd">{summary.data?.total ?? 0}</span>
                  </MenuItem>
                  <MenuSeparator />
                  <MenuItem icon="folder" checked={filter === ROOT} onSelect={() => showFolder(ROOT)}>
                    {t('library.noFolder')}<span className="kbd">{counts.get(ROOT) ?? 0}</span>
                  </MenuItem>
                  {folderList.map((folder) => (
                    <MenuItem key={folder.id} icon="folder" checked={filter === folder.id} onSelect={() => showFolder(folder.id)}>
                      {folder.name}<span className="kbd">{counts.get(folder.id) ?? 0}</span>
                    </MenuItem>
                  ))}
                </Menu>
                {!board && (<>
                <Menu
                  triggerClassName={view.sort !== 'updated' || view.direction ? 'tool is-on-blue' : 'tool'}
                  label={t('library.sortBy', { field: t(`library.sort.${view.sort}`) })}
                  trigger={<><Icon name="sort" />{t('library.sortBy', { field: t(`library.sort.${view.sort}`) })}</>}
                >
                  {LIBRARY_SORTS.map((sort) => (
                    <MenuItem key={sort} checked={view.sort === sort} onSelect={() => setView({ sort, direction: null })}>
                      {t(`library.sort.${sort}`)}
                    </MenuItem>
                  ))}
                  <MenuSeparator />
                  <MenuItem checked={direction === 'asc'} onSelect={() => setView({ direction: 'asc' })}>{t('library.sort.asc')}</MenuItem>
                  <MenuItem checked={direction === 'desc'} onSelect={() => setView({ direction: 'desc' })}>{t('library.sort.desc')}</MenuItem>
                </Menu>
                <Menu
                  triggerClassName={hidden.size > 0 ? 'tool is-on-blue' : 'tool'}
                  label={t('library.columns')}
                  trigger={<><Icon name="columns" />{t('library.columns')}</>}
                >
                  {LIBRARY_COLUMNS.map((column) => (
                    <MenuItem
                      key={column}
                      checked={!hidden.has(column)}
                      onSelect={() => setView({ hidden: hidden.has(column) ? view.hidden.filter((other) => other !== column) : [...view.hidden, column] })}
                    >
                      {t(`library.col.${column}`)}
                    </MenuItem>
                  ))}
                </Menu>
                </>)}
              </div>
              {board && <LibraryBoard space={space} folder={filter} archived={canManage(space)} />}
              {!board && (
              <div className="set-box is-flush" style={{ marginTop: 0 }}>
                <HorizontalScroll stickyFirstColumn>
                  <table className="grid" style={{ minWidth: 400 + 95 * (columns - 2) }}>
                    <thead>
                      <tr>
                        <th className="rn"><span className="sr-only">#</span></th>
                        <th className="primary"><span className="th"><Icon name="text" />{t('library.col.title')}</span></th>
                        {!hidden.has('status') && <th><span className="th"><Icon name="tag" />{t('library.col.status')}</span></th>}
                        {!hidden.has('category') && <th><span className="th"><Icon name="tag" />{t('library.col.category')}</span></th>}
                        {!hidden.has('owner') && <th><span className="th"><Icon name="user" />{t('library.col.owner')}</span></th>}
                        {!hidden.has('updated') && <th><span className="th"><Icon name="calendar" />{t('library.col.updated')}</span></th>}
                        {!hidden.has('version') && <th><span className="th"><Icon name="hash" />{t('library.col.version')}</span></th>}
                        {!hidden.has('indexing') && <th><span className="th"><Icon name="tag" />{t('library.col.indexing')}</span></th>}
                        {!hidden.has('language') && <th><span className="th"><Icon name="globe" />{t('library.col.language')}</span></th>}
                        {!hidden.has('format') && <th><span className="th"><Icon name="file" />{t('library.col.format')}</span></th>}
                      </tr>
                    </thead>
                    <tbody>
                      {groups.map((group) => {
                        const name = group.folder?.name ?? t('library.noFolder');
                        const open = !folded.has(group.key);
                        return (
                          <Fragment key={group.key}>
                            <tr className="group-row">
                              <td colSpan={columns}>
                                <div className="group-head">
                                  <button type="button" className="group-toggle" aria-expanded={open} onClick={() => toggleGroup(group.key)}>
                                    <Icon name="chevron" />
                                    <span className="lbl">{t('library.folder')}</span>
                                    <strong>{name}</strong>
                                    <span className="count">{counts.get(group.key) ?? group.documents.length}</span>
                                  </button>
                                  {contributor && group.folder && (
                                    <FolderMenu
                                      folder={group.folder}
                                      onRename={(folder) => setDialog({ kind: 'rename', folder })}
                                      onDelete={askDelete}
                                    />
                                  )}
                                </div>
                              </td>
                            </tr>
                            {open && group.documents.map((item) => (
                              <DocumentRow key={item.id} item={item} number={numbers.get(item.id) ?? 0} hidden={hidden} contributor={contributor} />
                            ))}
                            {open && group.documents.length === 0 && !documents.hasNextPage && (
                              <tr><td className="cell-note" colSpan={columns}>{t('library.emptyFolder')}</td></tr>
                            )}
                          </Fragment>
                        );
                      })}
                    </tbody>
                  </table>
                </HorizontalScroll>
                {groups.length === 0 && <p className="grid-empty">{t('library.emptyFolder')}</p>}
                <div className="grid-foot">
                  <span>{i18n.tp('library.count', foot.total)}</span>
                  {foot.inProgress > 0 && <span>{i18n.tp('library.inProgress', foot.inProgress)}</span>}
                  {foot.scanned > 0 && <span>{i18n.tp('library.scanned', foot.scanned)}</span>}
                  {foot.failed > 0 && <span>{i18n.tp('library.failed', foot.failed)}</span>}
                  {!onlyArchived && archivedCount > 0 && (
                    <Link className="link" to={`/spaces/${space.slug}?status=ARCHIVED`}>{i18n.tp('library.archived', archivedCount)}</Link>
                  )}
                  {documents.hasNextPage && (
                    <span className="pager">
                      <button type="button" className="btn" disabled={documents.isFetchingNextPage} onClick={() => void documents.fetchNextPage()}>
                        {documents.isFetchingNextPage && <Spinner />}
                        {t('common.loadMore')}
                      </button>
                    </span>
                  )}
                </div>
              </div>
              )}
            </div>
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
      {dialog?.kind === 'notEmpty' && (
        <Modal
          title={t('folder.notEmptyTitle', { name: dialog.folder.name })}
          onClose={() => setDialog(null)}
          footer={<button type="button" className="btn btn-primary" data-autofocus onClick={() => setDialog(null)}>{t('common.close')}</button>}
        >
          <p>{i18n.tp('folder.notEmptyBody', dialog.count)}</p>
        </Modal>
      )}
    </View>
  );
}

function FolderMenu({ folder, onRename, onDelete }: { folder: Folder; onRename: (folder: Folder) => void; onDelete: (folder: Folder) => void }) {
  const { t } = useI18n();
  return (
    <Menu align="end" label={t('folder.actionsFor', { name: folder.name })} trigger={<Icon name="dots" />}>
      <MenuItem icon="pencil" onSelect={() => onRename(folder)}>{t('folder.rename')}</MenuItem>
      <MenuItem icon="trash" danger onSelect={() => onDelete(folder)}>{t('folder.delete')}</MenuItem>
    </Menu>
  );
}

/** A line of the grid: the title with the icon of its format and a lock when restricted. */
function DocumentRow({ item, number, hidden, contributor }: {
  item: DocumentItem;
  number: number;
  hidden: Set<LibraryColumn>;
  /** A failed new version is told to who may import another. */
  contributor: boolean;
}) {
  const i18n = useI18n();
  const { t } = i18n;
  const newer = item.newerVersion?.extractionStatus === 'FAILED' && !contributor ? null : item.newerVersion;
  return (
    <tr className={item.status === 'OBSOLETE' ? 'row-obsolete' : undefined}>
      <td className="rn">{number}</td>
      <td className="primary">
        <div className="cell-title">
          <FormatIcon mediaType={item.mediaType} />
          <Link to={`/documents/${item.id}`}>{item.title}</Link>
          {item.restricted && (
            <span role="img" aria-label={t('library.restricted')} title={t('library.restricted')}><Icon name="lock" className="lock" /></span>
          )}
          {newer && (
            <span className="help">
              {t(newer.extractionStatus === 'FAILED' ? 'version.inFailure' : 'version.inProgress', { number: newer.number })}
            </span>
          )}
        </div>
      </td>
      {!hidden.has('status') && <td><StatusLabel status={item.status} /></td>}
      {!hidden.has('category') && <td>{item.category && <CategoryPill category={item.category} />}</td>}
      {!hidden.has('owner') && <td><Owner owner={item.owner} /></td>}
      {!hidden.has('updated') && <td>{i18n.relative(item.updatedAt)}</td>}
      {!hidden.has('version') && <td className="num">{item.version}</td>}
      {!hidden.has('indexing') && <td><IndexPill document={item} /></td>}
      {!hidden.has('language') && <td>{languageName(i18n, item.language)}</td>}
      {!hidden.has('format') && <td>{formatName(i18n, item.mediaType)}</td>}
    </tr>
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
      // The documents show the name of their folder.
      if (folder) void refreshAllDocuments();
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
