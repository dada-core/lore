import { useMutation, useQuery } from '@tanstack/react-query';
import { lazy, Suspense, useCallback, useEffect, useRef, useState, type FormEvent, type RefObject } from 'react';
import { Link, useLocation, useNavigate, useParams } from 'react-router';
import { api, ApiError, call } from '../api/client';
import { keys, queryClient } from '../api/queryClient';
import { errorMessage } from '../i18n/errors';
import { useI18n } from '../i18n/i18n';
import { isTyping, View, type Crumb } from '../layouts/AppShell';
import { ConfirmDialog } from '../pages/admin/MemberDialogs';
import { NotFoundPage } from '../pages/NotFoundPage';
import { fromSearch, searchAddress } from '../search/search';
import { SpaceIcon } from '../spaces/spaces';
import { Avatar, copyText, useDocumentTitle } from '../ui/bits';
import { Callout, checks, hasProblems, message, problemsOf, TextField, validate, type Problems } from '../ui/forms';
import { Icon, Spinner } from '../ui/Icon';
import { Menu, MenuItem, MenuSeparator } from '../ui/Menu';
import { Modal } from '../ui/Modal';
import { usePanel } from '../ui/preferences';
import { useNotify } from '../ui/Toast';
import { AccessDialog, DescriptionDialog, LinkDialog, MoveDialog, OwnerDialog } from './DocumentDialogs';
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
  LANGUAGES,
  languageName,
  Owner,
  refreshDocuments,
  sizeText,
  StatusLabel,
  whileExtracting,
  type Document,
} from './documents';
import { Outline, type Navigation, type OutlineItem } from './reader/Outline';
import { RenderedDocument } from './reader/RenderedDocument';
import { documentLink, readTarget, sectionLink } from './reader/target';

const MAX_TITLE = 200;
const DOCX = 'application/vnd.openxmlformats-officedocument.wordprocessingml.document';
type Dialog = 'access' | 'move' | 'delete' | 'description' | 'owner' | 'language';

// pdf.js and its viewer are loaded only when a PDF is opened.
const PdfViewer = lazy(() => import('./reader/PdfViewer'));

/**
 * The page of a document: its title and the banners that matter, then its content, the rendering
 * of a Markdown, Word or text document or the PDF itself, opened at the place of the address; its
 * properties, access, versions and outline on the side. Only what the person may do is offered.
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
  const location = useLocation();
  const [dialog, setDialog] = useState<Dialog | null>(null);
  const [editing, setEditing] = useState(false);
  const [propsShown, toggleProps] = usePanel('props');
  // Followed during this visit: the end of the extraction is then told to screen readers.
  const [followed, setFollowed] = useState(document.currentVersion.extractionStatus === 'PENDING');
  const [outline, setOutline] = useState<OutlineItem[]>([]);
  const [reading, setReading] = useState<string | null>(null);
  const [notice, setNotice] = useState<'missing' | null>(null);
  const [link, setLink] = useState<{ title: string; url: string } | null>(null);
  const navigation = useRef<Navigation | null>(null);
  const { actions, space, folder, currentVersion: version } = document;
  useDocumentTitle(document.title);
  // ]: the panel of the properties, as [ folds the sidebar; not while typing nor in a window.
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.key !== ']' || event.ctrlKey || event.metaKey || event.altKey || isTyping()) return;
      if (globalThis.document.querySelector('dialog[open]')) return;
      event.preventDefault();
      toggleProps();
    };
    globalThis.document.addEventListener('keydown', onKey);
    return () => globalThis.document.removeEventListener('keydown', onKey);
  }, [toggleProps]);
  // This opening is a view: "Resume" on the home page shows it next time.
  useEffect(() => {
    void queryClient.invalidateQueries({ queryKey: keys.recentDocuments });
  }, [document.id]);
  // The place of the address: the reader opens there. A window opened over the page
  // (?open=import) changes the address without reloading the reader.
  const [target, setTarget] = useState(() => readTarget(location.search, location.hash));
  // The place shown: the address follows it (outline, links of the document), and a link to
  // another place of the same document, followed afterwards, leads there.
  const shown = useRef(target.place);
  useEffect(() => {
    const next = readTarget(location.search, location.hash);
    if (!next.place || next.place === shown.current) return;
    shown.current = next.place;
    setTarget(next);
    setNotice(null);
    if (next.version === null || next.version === version.number) navigation.current?.go(next.place, next.query);
  }, [location.search, location.hash, version.number]);
  // The state of the address goes with it: a result opened keeps its words and its way back.
  const followPlace = useCallback((place: string) => {
    shown.current = place;
    navigate({ pathname: location.pathname, search: location.search, hash: place },
      { replace: true, preventScrollReset: true, state: location.state });
  }, [navigate, location.pathname, location.search, location.state]);
  const otherVersion = target.version !== null && target.version !== version.number;
  const pdf = isPdf(version.mediaType);
  const fileUrl = `/api/v1/documents/${document.id}/versions/${version.number}/file`;

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

  const changeLanguage = useMutation({
    mutationFn: (language: string) => call(() => api.PATCH('/api/v1/documents/{id}', {
      params: { path: { id: document.id } },
      body: { language },
    })),
    onSuccess: (changed) => {
      queryClient.setQueryData(keys.document(document.id), changed);
      void refreshDocuments(space.id);
      setDialog(null);
      notify(t('language.changed'));
    },
    onError: (error) => {
      setDialog(null);
      notify(errorMessage(i18n, error));
    },
  });
  const [language, setLanguage] = useState<string | null>(null);

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

  // Copied when the browser allows it; shown in a window otherwise (http:// on an internal network).
  const copyLink = useCallback(async (url: string, section: boolean) => {
    if (await copyText(url)) notify(t(section ? 'reader.sectionLinkCopied' : 'common.linkCopied'));
    else setLink({ title: t(section ? 'reader.sectionLinkTitle' : 'reader.linkTitle'), url });
  }, [notify, t]);
  const copySection = useCallback(
    (place: string) => void copyLink(sectionLink(document.id, version.number, place), true),
    [copyLink, document.id, version.number],
  );
  const print = () => {
    if (pdf) window.open(`${fileUrl}?disposition=inline`, '_blank', 'noopener');
    else window.print();
  };

  const status = version.extractionStatus;
  const fix = failureFix(i18n, version.extractionError);
  const outcome = status === 'INDEXED'
    ? indexedText(i18n, version)
    : status === 'SCANNED'
      ? t('document.scanned')
      : status === 'FAILED' ? `${t('failure.line', { cause: failureCause(i18n, version.extractionError) })}${fix ? ` ${fix}` : ''}` : '';
  const toggleLabel = propsShown ? t('document.hideProps') : t('document.showProps');
  const foundTitle = outline.find((item) => item.key === target.place)?.title;
  const foundAt = target.page !== null
    ? t('reader.atPage', { page: target.page })
    : foundTitle && t('reader.atSection', { title: foundTitle });
  const found = target.fromSearch && target.query.trim() !== '' && target.place !== null && notice !== 'missing' && !otherVersion;
  const shortQuery = target.query.length > 80 ? `${target.query.slice(0, 80)}…` : target.query;
  // A link made for another version opens at the top: the versions arrive with a later version of Lore.
  // From a result: the forms found ("serveur" for "serveurs") are highlighted too; a copied link
  // carries the words of q only.
  const search = target.fromSearch ? fromSearch(location.state) : null;
  const highlighted = search?.words.length ? { ...target, query: `${target.query} ${search.words.join(' ')}` } : target;
  const reader = otherVersion ? { ...target, place: null, page: null, query: '' } : highlighted;
  // Opened from the results: back in the history, which finds the list as it was. Opened from a
  // link: the results of its words.
  const backToResults = () => {
    // A page reached within Lore has an entry before it; the first page opened has the key "default".
    if (search && location.key !== 'default') navigate(-1);
    else navigate(searchAddress(target.query));
  };
  const fromResults = target.fromSearch && target.query.trim() !== '';

  const menu = (
    <Menu align="end" label={t('document.moreActions')} trigger={<Icon name="dots" />}>
      {actions.manageAccess && <MenuItem icon="lock" onSelect={() => setDialog('access')}>{t('document.manageAccess')}</MenuItem>}
      {actions.edit && <MenuItem icon="folder" onSelect={() => setDialog('move')}>{t('document.move')}</MenuItem>}
      {actions.changeOwner && <MenuItem icon="user" onSelect={() => setDialog('owner')}>{t('document.changeOwner')}</MenuItem>}
      {(actions.manageAccess || actions.edit || actions.changeOwner) && <MenuSeparator />}
      <MenuItem icon="link" onSelect={() => void copyLink(documentLink(document.id), false)}>{t('reader.copyLink')}</MenuItem>
      {pdf && version.fileAvailable && (
        <MenuItem icon="external" onSelect={() => window.open(`${fileUrl}?disposition=inline`, '_blank', 'noopener')}>
          {t('reader.openInBrowser')}
        </MenuItem>
      )}
      {(!pdf || version.fileAvailable) && <MenuItem icon="print" onSelect={print}>{t('reader.print')}</MenuItem>}
      {actions.delete && <MenuSeparator />}
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
          {version.fileAvailable && (
            <a className="icon-btn" href={fileUrl} aria-label={t('reader.download')} title={t('reader.download')}>
              <Icon name="download" />
            </a>
          )}
          {menu}
          <button type="button" className="icon-btn" onClick={toggleProps} aria-label={toggleLabel} title={`${toggleLabel} (])`} aria-keyshortcuts="]" aria-expanded={propsShown} aria-controls="document-props">
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
            {found && (
              <div className="callout callout-found" role="status">
                <Icon name="highlight" />
                <p>{foundAt ? t('reader.found', { query: shortQuery, place: foundAt }) : t('reader.foundHere', { query: shortQuery })}</p>
                <button type="button" className="btn" onClick={backToResults}>
                  <Icon name="chevron-left" />{t('search.backToResults')}
                </button>
              </div>
            )}
            {fromResults && !found && (
              <div className="from-search">
                <button type="button" className="btn" onClick={backToResults}>
                  <Icon name="chevron-left" />{t('search.backToResults')}
                </button>
              </div>
            )}
            {notice === 'missing' && <Callout role="status">{t('reader.missing')}</Callout>}
            {otherVersion && <Callout role="status">{t('reader.changed', { version: target.version ?? 0 })}</Callout>}
            {!version.fileAvailable && <Callout tone="error">{t('reader.fileMissing')}</Callout>}
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
            {version.textTruncated && (
              <Callout tone="warn">{t('document.truncated')}{!pdf && <> {t('reader.truncatedRest')}</>}</Callout>
            )}

            {editing ? (
              <TitleForm document={document} onDone={() => setEditing(false)} />
            ) : (
              <div className="doc-title-row">
                <h1 className="doc-title" lang={version.language === 'en' ? 'en' : undefined}>{document.title}</h1>
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
              {version.language === 'en' && <span className="tag-lang" title={t('language.en')}>EN</span>}
            </div>
            <div className="print-only print-head">
              <span>{t(`docStatus.${document.status as 'DRAFT'}`)}</span>
              <span>{t('document.version', { number: version.number })}</span>
              <span>{t('reader.printedOn', { date: i18n.date(new Date()) })}</span>
            </div>
            {version.mediaType === DOCX && status === 'INDEXED' && (
              <p className="doc-note">
                {t('reader.wordSimplified')}
                {version.fileAvailable && <> <a className="link" href={fileUrl}>{t('reader.download')}</a></>}
              </p>
            )}

            {document.description ? (
              <div className="doc-description-row">
                <p className="doc-description">{document.description}</p>
                {actions.edit && (
                  <button type="button" className="link" onClick={() => setDialog('description')}>{t('document.editDescription')}</button>
                )}
              </div>
            ) : actions.edit && (
              <button type="button" className="link add-description" onClick={() => setDialog('description')}>{t('document.addDescription')}</button>
            )}

            <div className="doc-body">
              {status !== 'FAILED' && pdf && version.fileAvailable && (
                <Suspense fallback={<div className="grid-empty" role="status"><Spinner /><span className="sr-only">{t('reader.loading')}</span></div>}>
                  <PdfViewer
                    document={document}
                    target={reader}
                    scanned={status === 'SCANNED'}
                    navigationRef={navigation}
                    onOutline={setOutline}
                    onNotice={setNotice}
                  />
                </Suspense>
              )}
              {!pdf && status === 'PENDING' && <p className="muted">{t('reader.pendingContent')}</p>}
              {!pdf && status === 'INDEXED' && (
                <RenderedDocument
                  key={version.id}
                  document={document}
                  target={reader}
                  navigationRef={navigation}
                  onOutline={setOutline}
                  onReading={setReading}
                  onCopySection={copySection}
                  onNotice={setNotice}
                  onShown={followPlace}
                />
              )}
            </div>
          </div>
        </div>

        <Properties
          document={document}
          outline={outline}
          reading={reading}
          navigationRef={navigation}
          onDialog={setDialog}
          onCategory={(category) => changeCategory.mutate(category)}
          onLanguage={(chosen) => {
            setLanguage(chosen);
            setDialog('language');
          }}
        />
      </div>

      {dialog === 'language' && language && (
        <Modal
          title={t('language.confirmTitle')}
          onClose={() => setDialog(null)}
          footer={(
            <>
              <button type="button" className="btn" onClick={() => setDialog(null)}>{t('common.cancel')}</button>
              <button
                type="button"
                className="btn btn-primary"
                data-autofocus
                disabled={changeLanguage.isPending}
                onClick={() => changeLanguage.mutate(language)}
              >
                {changeLanguage.isPending && <Spinner />}
                {t('language.confirm')}
              </button>
            </>
          )}
        >
          <p>{t('language.confirmBody')}</p>
        </Modal>
      )}
      {dialog === 'access' && <AccessDialog document={document} onClose={() => setDialog(null)} />}
      {dialog === 'move' && <MoveDialog document={document} onClose={() => setDialog(null)} />}
      {dialog === 'description' && <DescriptionDialog document={document} onClose={() => setDialog(null)} />}
      {dialog === 'owner' && (
        <OwnerDialog
          document={document}
          onClose={() => setDialog(null)}
          onHandedOver={(name) => {
            void queryClient.invalidateQueries({ queryKey: keys.document(document.id), refetchType: 'none' });
            notify(t('owner.handedOver', { name }));
            navigate(libraryOf(folder?.id ?? null), { replace: true });
          }}
        />
      )}
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
      {link && <LinkDialog title={link.title} link={link.url} onClose={() => setLink(null)} />}
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

/** The panel on the right: properties, access, versions and the outline of the document. */
function Properties({ document, outline, reading, navigationRef, onDialog, onCategory, onLanguage }: {
  document: Document;
  outline: OutlineItem[];
  reading: string | null;
  navigationRef: RefObject<Navigation | null>;
  onDialog: (dialog: Dialog) => void;
  onCategory: (category: string) => void;
  /** A language chosen in the menu, to confirm. */
  onLanguage: (language: string) => void;
}) {
  const i18n = useI18n();
  const { t } = i18n;
  const notify = useNotify();
  const { actions, space, folder, currentVersion: version } = document;
  const access = useQuery({
    queryKey: keys.documentAccess(document.id),
    queryFn: () => call(() => api.GET('/api/v1/documents/{id}/access', { params: { path: { id: document.id } } })),
  });
  const folderName = folder?.name ?? t('library.noFolder');
  // "PDF, 312 pages, 24,8 Mo", "Markdown, 14 sections, 18 Ko".
  const counted = isPdf(version.mediaType) && version.pageCount != null
    ? i18n.tp('document.pageCount', version.pageCount)
    : version.sectionCount != null && version.extractionStatus === 'INDEXED' ? i18n.tp('document.sectionCount', version.sectionCount) : null;
  const format = [formatName(i18n, version.mediaType), counted, sizeText(i18n, version.sizeBytes)].filter(Boolean).join(', ');
  const fileUrl = `/api/v1/documents/${document.id}/versions/${version.number}/file`;

  return (
    <aside className="props" id="document-props" aria-label={t('document.properties')}>
      <section>
        <h2>{t('document.properties')}</h2>
        <dl>
          <div className="prop"><dt>{t('document.prop.status')}</dt><dd><span className="prop-val prop-static"><StatusLabel status={document.status} /></span></dd></div>
          <div className="prop">
            <dt>{t('document.prop.owner')}</dt>
            <dd>
              {actions.changeOwner ? (
                <>
                  <button type="button" className="prop-val" aria-describedby="owner-change" onClick={() => onDialog('owner')}>
                    <Owner owner={document.owner} />
                  </button>
                  <span className="sr-only" id="owner-change">{t('document.changeOwner')}</span>
                </>
              ) : (
                <span className="prop-val prop-static"><Owner owner={document.owner} /></span>
              )}
            </dd>
          </div>
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
            <dd>
              {actions.edit && version.extractionStatus !== 'PENDING' ? (
                <Menu
                  triggerClassName="prop-val"
                  label={t('language.menu', { name: languageName(i18n, version.language) ?? t('document.languageUnknown') })}
                  trigger={languageName(i18n, version.language) ?? <span className="muted">{t('document.languageUnknown')}</span>}
                >
                  {LANGUAGES.map((language) => (
                    <MenuItem
                      key={language}
                      checked={version.language === language}
                      onSelect={() => version.language !== language && onLanguage(language)}
                    >
                      {languageName(i18n, language)}
                    </MenuItem>
                  ))}
                </Menu>
              ) : (
                <span className="prop-val prop-static">{languageName(i18n, version.language) ?? <span className="muted">{t('document.languageUnknown')}</span>}</span>
              )}
            </dd>
          </div>
          <div className="prop">
            <dt>{t('document.prop.format')}</dt>
            <dd><span className="prop-val prop-static"><FormatIcon mediaType={version.mediaType} /><span>{format}</span></span></dd>
          </div>
          <div className="prop">
            <dt>{t('document.prop.indexing')}</dt>
            <dd>
              <span className="prop-val prop-static prop-stack">
                <IndexPill document={version} />
                {version.extractedAt && version.extractionStatus !== 'PENDING' && (
                  <span className="help">{t('document.extractedOn', { date: i18n.date(version.extractedAt) })}</span>
                )}
              </span>
            </dd>
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
          <span className="fingerprint">
            <span className="help">{t('document.fingerprint')}</span>
            <code title={version.sha256}>{version.sha256.slice(0, 16)}…</code>
            <button
              type="button"
              className="icon-btn"
              aria-label={t('document.copyFingerprint')}
              title={t('document.copyFingerprint')}
              onClick={async () => notify(t((await copyText(version.sha256)) ? 'document.fingerprintCopied' : 'common.copyFailed'))}
            >
              <Icon name="copy" />
            </button>
          </span>
          {version.fileAvailable && (
            <a className="btn" href={fileUrl}><Icon name="download" />{t('document.downloadVersion')}</a>
          )}
        </div>
      </section>

      <Outline items={outline} current={reading} navigationRef={navigationRef} linkable={!isPdf(version.mediaType)} />
    </aside>
  );
}
