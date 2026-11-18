import { useQuery } from '@tanstack/react-query';
import { lazy, Suspense, useCallback, useRef, useState } from 'react';
import { Link, Navigate, useLocation, useNavigate, useParams } from 'react-router';
import { api, ApiError, call } from '../api/client';
import { keys } from '../api/queryClient';
import { errorMessage } from '../i18n/errors';
import { useI18n } from '../i18n/i18n';
import { View, type Crumb } from '../layouts/AppShell';
import { NotFoundPage } from '../pages/NotFoundPage';
import { copyText, useDocumentTitle } from '../ui/bits';
import { Callout } from '../ui/forms';
import { Icon, Spinner } from '../ui/Icon';
import { useNotify } from '../ui/Toast';
import { LinkDialog } from './DocumentDialogs';
import { failureCause, isPdf, StatusLabel, type Document, type Version } from './documents';
import { Outline, type Navigation, type OutlineItem } from './reader/Outline';
import { RenderedDocument } from './reader/RenderedDocument';
import { readTarget } from './reader/target';

const PdfViewer = lazy(() => import('./reader/PdfViewer'));

/**
 * An older version of a document, read only: its rendering or its PDF, its outline and the links
 * of its sections, under "You are reading version 3 of 2 Sept 2026. The current version is 5."
 * Opening it is a view, recorded with its number. The search never leads here.
 */
export function VersionPage() {
  const i18n = useI18n();
  const { id = '', number = '' } = useParams();
  const wanted = Number.parseInt(number, 10);
  const document = useQuery({
    queryKey: [...keys.document(id), 'shown', wanted],
    queryFn: () => call(() => api.GET('/api/v1/documents/{id}', { params: { path: { id }, query: { version: wanted } } })),
    enabled: Number.isInteger(wanted) && wanted > 0,
  });
  const version = useQuery({
    queryKey: [...keys.documentVersions(id), wanted],
    queryFn: () => call(() => api.GET('/api/v1/documents/{id}/versions/{number}', { params: { path: { id, number: wanted } } })),
    enabled: Number.isInteger(wanted) && wanted > 0,
  });

  const missing = (error: unknown) => error instanceof ApiError && error.status === 404;
  if (!Number.isInteger(wanted) || wanted < 1 || missing(document.error) || missing(version.error)) return <NotFoundPage />;
  if (document.data && wanted === document.data.currentVersion.number) {
    return <Navigate to={`/documents/${id}`} replace />;
  }
  if (!document.data || !version.data) {
    const error = document.error ?? version.error;
    return (
      <View crumbs={[{ label: i18n.t('nav.spaces'), to: '/spaces' }, { label: '…' }]}>
        <div className="pad">
          {error
            ? <Callout tone="error" role="alert">{errorMessage(i18n, error)}</Callout>
            : <div className="grid-empty" role="status"><Spinner /><span className="sr-only">{i18n.t('common.loading')}</span></div>}
        </div>
      </View>
    );
  }
  return <VersionView document={document.data} version={version.data} />;
}

function VersionView({ document, version }: { document: Document; version: Version }) {
  const i18n = useI18n();
  const { t } = i18n;
  const notify = useNotify();
  const navigate = useNavigate();
  const location = useLocation();
  const navigation = useRef<Navigation | null>(null);
  const [outline, setOutline] = useState<OutlineItem[]>([]);
  const [reading, setReading] = useState<string | null>(null);
  const [notice, setNotice] = useState<'missing' | null>(null);
  const [link, setLink] = useState<string | null>(null);
  const [target] = useState(() => readTarget(location.search, location.hash));
  // The readers show a version as the current one of a document: this one, here.
  const shown: Document = { ...document, currentVersion: version };
  const pdf = isPdf(version.mediaType);
  const base = `/documents/${document.id}/versions/${version.number}`;
  useDocumentTitle(`${document.title} (${t('versions.other', { number: version.number })})`);

  const followPlace = useCallback((place: string) => {
    navigate({ pathname: location.pathname, hash: place }, { replace: true, preventScrollReset: true });
  }, [navigate, location.pathname]);
  const copySection = useCallback(async (place: string) => {
    const url = `${window.location.origin}${base}#${place}`;
    if (await copyText(url)) notify(t('reader.sectionLinkCopied'));
    else setLink(url);
  }, [base, notify, t]);

  const crumbs: Crumb[] = [
    { label: t('nav.spaces'), to: '/spaces' },
    { label: document.space.name, to: `/spaces/${document.space.slug}` },
    { label: document.title, to: `/documents/${document.id}` },
    { label: t('versions.other', { number: version.number }) },
  ];
  const fileUrl = `/api/v1/documents/${document.id}/versions/${version.number}/file`;
  const status = version.extractionStatus;

  return (
    <View
      crumbs={crumbs}
      actions={version.fileAvailable && (
        <a className="icon-btn" href={fileUrl} aria-label={t('reader.download')} title={t('reader.download')}>
          <Icon name="download" />
        </a>
      )}
    >
      <div className="doc-view">
        <div className="doc-main">
          <div className="doc-inner">
            <Callout role="status">
              <div className="row-between">
                <span>
                  {t('version.viewing', {
                    number: version.number,
                    date: i18n.date(version.createdAt),
                    current: document.currentVersion.number,
                  })}
                </span>
                <Link className="btn" to={`/documents/${document.id}`}>{t('version.backToCurrent')}</Link>
              </div>
            </Callout>
            {notice === 'missing' && <Callout role="status">{t('reader.missing')}</Callout>}
            {!version.fileAvailable && <Callout tone="error">{t('reader.fileMissing')}</Callout>}
            {status === 'PENDING' && (
              <div className="callout" role="status"><Spinner /><p>{t('version.pending')}</p></div>
            )}
            {status === 'FAILED' && (
              <Callout tone="error">{t('version.failed', { number: version.number, cause: failureCause(i18n, version.extractionError) })}</Callout>
            )}
            <h1 className="doc-title">{document.title}</h1>
            <div className="doc-sub">
              <StatusLabel status={document.status} />
              <span>{t('versions.other', { number: version.number })}</span>
              <span>
                {version.author
                  ? t('versions.importedBy', { date: i18n.date(version.createdAt), name: version.author.displayName })
                  : t('versions.importedOn', { date: i18n.date(version.createdAt) })}
              </span>
            </div>
            {version.note && <p className="doc-description">{version.note}</p>}
            <div className="doc-body">
              {status !== 'FAILED' && status !== 'PENDING' && pdf && version.fileAvailable && (
                <Suspense fallback={<div className="grid-empty" role="status"><Spinner /><span className="sr-only">{t('reader.loading')}</span></div>}>
                  <PdfViewer
                    document={shown}
                    target={target}
                    scanned={status === 'SCANNED'}
                    navigationRef={navigation}
                    onOutline={setOutline}
                    onNotice={setNotice}
                  />
                </Suspense>
              )}
              {!pdf && status === 'INDEXED' && (
                <RenderedDocument
                  key={version.id}
                  document={shown}
                  target={target}
                  navigationRef={navigation}
                  onOutline={setOutline}
                  onReading={setReading}
                  onCopySection={(place) => void copySection(place)}
                  onNotice={setNotice}
                  onShown={followPlace}
                />
              )}
            </div>
          </div>
        </div>
        <aside className="props" aria-label={t('document.properties')}>
          <Outline items={outline} current={reading} navigationRef={navigation} linkable={!pdf} />
        </aside>
      </div>
      {link && <LinkDialog title={t('reader.sectionLinkTitle')} link={link} onClose={() => setLink(null)} />}
    </View>
  );
}
