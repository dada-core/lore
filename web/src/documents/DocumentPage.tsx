import { useMutation, useQuery } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import { api, ApiError, call } from '../api/client';
import { keys, queryClient } from '../api/queryClient';
import { errorMessage } from '../i18n/errors';
import { useI18n } from '../i18n/i18n';
import { View, type Crumb } from '../layouts/AppShell';
import { ConfirmDialog } from '../pages/admin/MemberDialogs';
import { NotFoundPage } from '../pages/NotFoundPage';
import { SpaceIcon } from '../spaces/spaces';
import { Avatar, useDocumentTitle } from '../ui/bits';
import { Callout, checks, hasProblems, message, problemsOf, TextField, validate, type Problems } from '../ui/forms';
import { Icon, Spinner } from '../ui/Icon';
import { Menu, MenuItem, MenuSeparator } from '../ui/Menu';
import { usePanel } from '../ui/preferences';
import { useNotify } from '../ui/Toast';
import { AccessDialog, DescriptionDialog, MoveDialog } from './DocumentDialogs';
import {
  CATEGORIES,
  categoryName,
  CategoryPill,
  documentQuery,
  failureCause,
  failureFix,
  FormatIcon,
  formatName,
  IndexPill,
  indexedText,
  isPdf,
  languageName,
  Owner,
  refreshDocuments,
  sizeText,
  StatusLabel,
  whileExtracting,
  type Document,
} from './documents';

const MAX_TITLE = 200;
type Dialog = 'access' | 'move' | 'delete' | 'description';

/**
 * The page of a document, without its content for now (the reader comes later): its title,
 * the state of its extraction, its description and what was extracted, and its properties, its
 * access and its version on the side. Only what the person may do is offered.
 */
export function DocumentPage() {
  const i18n = useI18n();
  const { id = '' } = useParams();
  const document = useQuery({
    ...documentQuery(id),
    // While its text is extracted, the page asks again every ten seconds; not after an error.
    refetchInterval: whileExtracting((data) => data.currentVersion.extractionStatus === 'PENDING'),
  });

  if (document.error instanceof ApiError && document.error.status === 404) return <NotFoundPage />;
  if (!document.data) {
    return (
      <View crumbs={[{ label: i18n.t('nav.spaces'), to: '/spaces' }, { label: '…' }]}>
        <div className="pad">
          {document.isError
            ? <Callout tone="error" role="alert">{errorMessage(i18n, document.error)}</Callout>
            : <div className="grid-empty" role="status"><Spinner /><span className="sr-only">{i18n.t('common.loading')}</span></div>}
        </div>
      </View>
    );
  }
  return <DocumentView key={document.data.id} document={document.data} />;
}

function DocumentView({ document }: { document: Document }) {
  const i18n = useI18n();
  const { t } = i18n;
  const notify = useNotify();
  const navigate = useNavigate();
  const [dialog, setDialog] = useState<Dialog | null>(null);
  const [editing, setEditing] = useState(false);
  const [propsShown, toggleProps] = usePanel('props');
  // Followed during this visit: the end of the extraction is then told to screen readers.
  const [followed, setFollowed] = useState(document.currentVersion.extractionStatus === 'PENDING');
  const { actions, space, folder, currentVersion: version } = document;
  useDocumentTitle(document.title);

  const libraryOf = (folderId: string | null) => `/spaces/${space.slug}?folder=${folderId ?? 'none'}`;
  const crumbs: Crumb[] = [
    { label: t('nav.spaces'), to: '/spaces' },
    { label: space.name, to: `/spaces/${space.slug}` },
    { label: folder?.name ?? t('library.noFolder'), to: libraryOf(folder?.id ?? null) },
    { label: document.title },
  ];

  // An empty category removes it.
  const changeCategory = useMutation({
    mutationFn: (category: string) => call(() => api.PATCH('/api/v1/documents/{id}', {
      params: { path: { id: document.id } },
      body: { category },
    })),
    onSuccess: (changed) => {
      queryClient.setQueryData(keys.document(document.id), changed);
      void refreshDocuments(space.id);
      notify(t('document.categorySaved'));
    },
    onError: (error) => notify(errorMessage(i18n, error)),
  });

  const retry = useMutation({
    mutationFn: () => call(() => api.POST('/api/v1/documents/{id}/extraction/retry', { params: { path: { id: document.id } } })),
    onSuccess: (retried) => {
      queryClient.setQueryData(keys.document(document.id), retried);
      setFollowed(true);
      void refreshDocuments(space.id);
      notify(t('extraction.retried'));
    },
    onError: (error) => notify(errorMessage(i18n, error)),
  });

  const remove = useMutation({
    mutationFn: () => call(() => api.DELETE('/api/v1/documents/{id}', { params: { path: { id: document.id } } })),
    onSuccess: () => {
      // The page is left at once; coming back to it asks the server again, which answers 404.
      void queryClient.invalidateQueries({ queryKey: keys.document(document.id), refetchType: 'none' });
      void refreshDocuments(space.id);
      notify(t('document.deleted'));
      navigate(libraryOf(folder?.id ?? null), { replace: true });
    },
  });

  const status = version.extractionStatus;
  const fix = failureFix(i18n, version.extractionError);
  const outcome = status === 'INDEXED'
    ? indexedText(i18n, version)
    : status === 'SCANNED'
      ? t('document.scanned')
      : status === 'FAILED' ? `${t('failure.line', { cause: failureCause(i18n, version.extractionError) })}${fix ? ` ${fix}` : ''}` : '';
  const toggleLabel = propsShown ? t('document.hideProps') : t('document.showProps');
  const menu = (actions.manageAccess || actions.edit || actions.delete) && (
    <Menu align="end" label={t('document.moreActions')} trigger={<Icon name="dots" />}>
      {actions.manageAccess && <MenuItem icon="lock" onSelect={() => setDialog('access')}>{t('document.manageAccess')}</MenuItem>}
      {actions.edit && <MenuItem icon="folder" onSelect={() => setDialog('move')}>{t('document.move')}</MenuItem>}
      {actions.delete && (actions.manageAccess || actions.edit) && <MenuSeparator />}
      {actions.delete && (
        <MenuItem icon="trash" danger onSelect={() => { remove.reset(); setDialog('delete'); }}>{t('document.delete')}</MenuItem>
      )}
    </Menu>
  );

  return (
    <View
      crumbs={crumbs}
      actions={(
        <>
          {menu}
          <button type="button" className="icon-btn" onClick={toggleProps} aria-label={toggleLabel} title={toggleLabel} aria-expanded={propsShown} aria-controls="document-props">
            <Icon name="sidebar-right" />
          </button>
        </>
      )}
    >
      <div className={propsShown ? 'doc-view' : 'doc-view hide-props'}>
        <div className="doc-main">
          <div className="doc-inner">
            <div className="sr-only" role="status">
              {followed && status !== 'PENDING' && `${t('document.extracted')} ${outcome}`}
            </div>
            {status === 'PENDING' && (
              <div className="callout" role="status">
                <Spinner />
                <p>{t('document.extracting')}</p>
              </div>
            )}
            {status === 'SCANNED' && (
              <Callout tone="warn">
                <div className="row-between">
                  <span>{t('document.scanned')}</span>
                  {actions.edit && (
                    <button type="button" className="btn" onClick={() => setDialog('description')}>
                      {t(document.description ? 'document.editDescription' : 'document.addDescription')}
                    </button>
                  )}
                </div>
              </Callout>
            )}
            {status === 'FAILED' && (
              <Callout tone="error">
                <div className="row-between">
                  <span>
                    {t('failure.line', { cause: failureCause(i18n, version.extractionError) })}
                    {fix && <> {fix}</>}
                  </span>
                  {actions.retry && (
                    <button type="button" className="btn" disabled={retry.isPending} onClick={() => retry.mutate()}>
                      {retry.isPending ? <Spinner /> : <Icon name="refresh" />}
                      {t('extraction.retry')}
                    </button>
                  )}
                </div>
              </Callout>
            )}
            {version.textTruncated && <Callout tone="warn">{t('document.truncated')}</Callout>}

            {editing ? (
              <TitleForm document={document} onDone={() => setEditing(false)} />
            ) : (
              <div className="doc-title-row">
                <h1 className="doc-title">{document.title}</h1>
                {actions.edit && (
                  <button type="button" className="icon-btn" onClick={() => setEditing(true)} aria-label={t('document.editTitle')} title={t('document.editTitle')}>
                    <Icon name="pencil" />
                  </button>
                )}
              </div>
            )}
            <div className="doc-sub">
              <StatusLabel status={document.status} />
              <span>{t('document.version', { number: version.number })}</span>
              <span>{t('document.updated', { when: i18n.relative(document.updatedAt) })}</span>
              {document.restricted && <span className="status"><Icon name="lock" className="lock" />{t('library.restricted')}</span>}
            </div>

            <section className="doc-section" aria-labelledby="document-description">
              <h2 id="document-description">
                {t('document.description')}
                {actions.edit && (
                  <button type="button" className="link" onClick={() => setDialog('description')}>
                    {t(document.description ? 'document.editDescription' : 'document.addDescription')}
                  </button>
                )}
              </h2>
              {document.description
                ? <p className="doc-description">{document.description}</p>
                : <p className="muted">{t('document.noDescription')}</p>}
            </section>

            <section className="doc-section" aria-labelledby="document-text">
              <h2 id="document-text">{t('document.text')}</h2>
              {status === 'INDEXED' || status === 'SCANNED' ? (
                <dl className="facts">
                  {version.pageCount != null && <div><dt>{t('document.pages')}</dt><dd className="num">{version.pageCount}</dd></div>}
                  {version.sectionCount != null && <div><dt>{t('document.sections')}</dt><dd className="num">{version.sectionCount}</dd></div>}
                  <div><dt>{t('document.language')}</dt><dd>{languageName(i18n, version.language) ?? t('document.languageUnknown')}</dd></div>
                  {version.extractedAt && <div><dt>{t('document.extractedAt')}</dt><dd>{i18n.date(version.extractedAt)}</dd></div>}
                </dl>
              ) : (
                <p className="muted">{t('document.notExtracted')}</p>
              )}
            </section>

            <Callout>{t('document.reader')}</Callout>
          </div>
        </div>

        <Properties document={document} onDialog={setDialog} onCategory={(category) => changeCategory.mutate(category)} />
      </div>

      {dialog === 'access' && <AccessDialog document={document} onClose={() => setDialog(null)} />}
      {dialog === 'move' && <MoveDialog document={document} onClose={() => setDialog(null)} />}
      {dialog === 'description' && <DescriptionDialog document={document} onClose={() => setDialog(null)} />}
      {dialog === 'delete' && (
        <ConfirmDialog
          title={t('document.deleteTitle', { title: document.title })}
          body={t('document.deleteBody')}
          confirm={t('document.delete')}
          pending={remove.isPending}
          error={remove.error}
          onConfirm={() => remove.mutate()}
          onClose={() => setDialog(null)}
        />
      )}
    </View>
  );
}

/** The title, changed in place: Enter saves, Escape gives up. */
function TitleForm({ document, onDone }: { document: Document; onDone: () => void }) {
  const i18n = useI18n();
  const { t } = i18n;
  const notify = useNotify();
  const [title, setTitle] = useState(document.title);
  const [problems, setProblems] = useState<Problems>({});

  const save = useMutation({
    mutationFn: () => call(() => api.PATCH('/api/v1/documents/{id}', { params: { path: { id: document.id } }, body: { title: title.trim() } })),
    onSuccess: (changed) => {
      queryClient.setQueryData(keys.document(document.id), changed);
      void refreshDocuments(document.space.id);
      notify(t('document.titleSaved'));
      onDone();
    },
    onError: (error) => setProblems(problemsOf(error)),
  });

  const submit = (event: FormEvent) => {
    event.preventDefault();
    if (title.trim() === document.title) {
      onDone();
      return;
    }
    const found = validate({ title: checks.required(title, MAX_TITLE) });
    setProblems(found);
    if (!hasProblems(found)) save.mutate();
  };

  return (
    <form className="title-form" noValidate onSubmit={submit}>
      {save.isError && !hasProblems(problemsOf(save.error)) && <Callout tone="error" role="alert">{errorMessage(i18n, save.error)}</Callout>}
      <TextField
        label={t('document.titleLabel')}
        value={title}
        autoFocus
        onChange={(event) => setTitle(event.target.value)}
        onKeyDown={(event) => {
          if (event.key === 'Escape') {
            event.preventDefault();
            onDone();
          }
        }}
        error={message(i18n, problems.title)}
      />
      <div className="row-end" style={{ justifyContent: 'flex-start' }}>
        <button type="submit" className="btn btn-primary" disabled={save.isPending}>
          {save.isPending && <Spinner />}
          {t('common.save')}
        </button>
        <button type="button" className="btn" onClick={onDone}>{t('common.cancel')}</button>
      </div>
    </form>
  );
}

/** The panel on the right: properties, access, version. */
function Properties({ document, onDialog, onCategory }: {
  document: Document;
  onDialog: (dialog: Dialog) => void;
  onCategory: (category: string) => void;
}) {
  const i18n = useI18n();
  const { t } = i18n;
  const { actions, space, folder, currentVersion: version } = document;
  const access = useQuery({
    queryKey: keys.documentAccess(document.id),
    queryFn: () => call(() => api.GET('/api/v1/documents/{id}/access', { params: { path: { id: document.id } } })),
  });
  const folderName = folder?.name ?? t('library.noFolder');
  const format = isPdf(version.mediaType) && version.pageCount != null
    ? `${formatName(i18n, version.mediaType)}, ${i18n.tp('document.pageCount', version.pageCount)}`
    : formatName(i18n, version.mediaType);

  return (
    <aside className="props" id="document-props" aria-label={t('document.properties')}>
      <section>
        <h2>{t('document.properties')}</h2>
        <dl>
          <div className="prop"><dt>{t('document.prop.status')}</dt><dd><span className="prop-val prop-static"><StatusLabel status={document.status} /></span></dd></div>
          <div className="prop"><dt>{t('document.prop.owner')}</dt><dd><span className="prop-val prop-static"><Owner owner={document.owner} /></span></dd></div>
          <div className="prop">
            <dt>{t('document.prop.category')}</dt>
            <dd>
              {actions.edit ? (
                <Menu
                  triggerClassName="prop-val"
                  label={t('document.categoryOf', { name: categoryName(i18n, document.category) })}
                  trigger={document.category ? <CategoryPill category={document.category} /> : <span className="muted">{t('category.none')}</span>}
                >
                  <MenuItem checked={!document.category} onSelect={() => document.category && onCategory('')}>{t('category.none')}</MenuItem>
                  {CATEGORIES.map((category) => (
                    <MenuItem key={category} checked={document.category === category} onSelect={() => document.category !== category && onCategory(category)}>
                      <CategoryPill category={category} />
                    </MenuItem>
                  ))}
                </Menu>
              ) : (
                <span className="prop-val prop-static">
                  {document.category ? <CategoryPill category={document.category} /> : <span className="muted">{t('category.none')}</span>}
                </span>
              )}
            </dd>
          </div>
          <div className="prop">
            <dt>{t('document.prop.space')}</dt>
            <dd><Link className="prop-val" to={`/spaces/${space.slug}`}><SpaceIcon space={space} /><span>{space.name}</span></Link></dd>
          </div>
          <div className="prop">
            <dt>{t('document.prop.folder')}</dt>
            <dd>
              {actions.edit ? (
                <button type="button" className="prop-val" aria-label={t('document.folderOf', { name: folderName })} onClick={() => onDialog('move')}>
                  <Icon name="folder" /><span>{folderName}</span>
                </button>
              ) : (
                <Link className="prop-val" to={`/spaces/${space.slug}?folder=${folder?.id ?? 'none'}`}><Icon name="folder" /><span>{folderName}</span></Link>
              )}
            </dd>
          </div>
          <div className="prop">
            <dt>{t('document.prop.language')}</dt>
            <dd><span className="prop-val prop-static">{languageName(i18n, version.language) ?? <span className="muted">{t('document.languageUnknown')}</span>}</span></dd>
          </div>
          <div className="prop">
            <dt>{t('document.prop.format')}</dt>
            <dd><span className="prop-val prop-static"><FormatIcon mediaType={version.mediaType} /><span>{format}</span></span></dd>
          </div>
          <div className="prop">
            <dt>{t('document.prop.size')}</dt>
            <dd><span className="prop-val prop-static">{sizeText(i18n, version.sizeBytes)}</span></dd>
          </div>
          <div className="prop">
            <dt>{t('document.prop.indexing')}</dt>
            <dd><span className="prop-val prop-static"><IndexPill document={version} /></span></dd>
          </div>
        </dl>
      </section>

      <section aria-labelledby="document-access">
        <h2 id="document-access">{t('document.access')}</h2>
        {access.isError && <Callout tone="error">{errorMessage(i18n, access.error)}</Callout>}
        {access.data && !access.data.restricted && (
          <p className="access-note">{i18n.tp('document.accessAll', access.data.spaceMembers, { name: space.name })}</p>
        )}
        {access.data?.restricted && (
          <>
            <p className="access-note">{t('document.accessRestricted')}</p>
            {access.data.people.map((person) => (
              <div className="grant" key={person.id}>
                <Avatar name={person.displayName} seed={person.id} />
                <span>{person.displayName}</span>
                {!person.reasons.includes('chosen') && (
                  <span className="note">{t(person.reasons.includes('owner') ? 'access.reason.owner' : 'access.reason.maintainer')}</span>
                )}
              </div>
            ))}
          </>
        )}
        {actions.manageAccess && (
          <button type="button" className="btn" onClick={() => onDialog('access')}>
            <Icon name="lock" />
            {t(document.restricted ? 'document.changeAccess' : 'document.restrictAccess')}
          </button>
        )}
      </section>

      <section aria-labelledby="document-versions">
        <h2 id="document-versions">{t('document.versions')}</h2>
        <div className="version-card">
          <strong>{t('document.version', { number: version.number })}</strong>
          <span className="help">
            {version.author
              ? t('document.versionBy', { date: i18n.date(version.createdAt), name: version.author.displayName })
              : t('document.versionOn', { date: i18n.date(version.createdAt) })}
          </span>
          <span className="help path">{version.filename}</span>
        </div>
      </section>
    </aside>
  );
}
