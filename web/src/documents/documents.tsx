import { useQuery, type InfiniteData } from '@tanstack/react-query';
import { useSearchParams } from 'react-router';
import { api, call, type Schemas } from '../api/client';
import { keys, queryClient } from '../api/queryClient';
import { useI18n, type I18n } from '../i18n/i18n';
import type { MessageKey } from '../i18n/messages';
import { refreshSpaces } from '../spaces/spaces';
import { Avatar } from '../ui/bits';
import { Icon, type IconName } from '../ui/Icon';

export type Document = Schemas['DocumentView'];
export type DocumentItem = Schemas['DocumentItem'];
export type ImportLimits = Schemas['ImportLimits'];

/** The seven categories of a document, the same list as the server's. */
export const CATEGORIES = ['RUNBOOK', 'OPERATING_PROCEDURE', 'INCIDENT_REPORT', 'SECURITY', 'VENDOR', 'ARCHITECTURE', 'TECHNICAL'] as const;
export type Category = (typeof CATEGORIES)[number];
const CATEGORY_PILLS: Record<Category, string> = {
  RUNBOOK: 'p-blue',
  OPERATING_PROCEDURE: 'p-teal',
  INCIDENT_REPORT: 'p-red',
  SECURITY: 'p-purple',
  VENDOR: 'p-yellow',
  ARCHITECTURE: 'p-cyan',
  TECHNICAL: 'p-gray',
};

/** The statuses a document can be imported with; the others come with the versions. */
export const IMPORT_STATUSES = ['DRAFT', 'PUBLISHED'] as const;
type DocumentStatus = 'DRAFT' | 'PUBLISHED' | 'OBSOLETE' | 'ARCHIVED';
type ExtractionStatus = 'PENDING' | 'INDEXED' | 'SCANNED' | 'FAILED';

/** Causes of a failed extraction that may pass: Lore tries again, then offers "Retry". Same list as ExtractionResults.PASSING_FAILURES on the server, checked by PassingFailuresTests. */
const PASSING_FAILURES = new Set(['timeout', 'extractor_crashed', 'file_missing']);
const FAILURES = new Set(['encrypted', 'corrupt', 'unsupported_content', ...PASSING_FAILURES]);
/** Causes that only a new file fixes: the interface says what to do instead of "Retry". */
const FIXES = new Set(['encrypted', 'corrupt', 'unsupported_content']);

/**
 * How often the screens ask again while an extraction is in progress, and the shortest time
 * between two refreshes of the lists during an import; shortened by the tests.
 */
export const timing = { follow: 2000, refresh: 10_000, group: 1000 };

/**
 * The interval of a query that follows extractions: every ten seconds while one is in progress,
 * never after an error (a document deleted in the meantime answers 404).
 */
export function whileExtracting<T>(inProgress: (data: T) => boolean) {
  return (query: { state: { data: T | undefined; status: string } }): number | false => {
    const { data, status } = query.state;
    return status !== 'error' && data !== undefined && inProgress(data) ? timing.refresh : false;
  };
}

export function isPassingFailure(code: string | null | undefined): boolean {
  return !!code && PASSING_FAILURES.has(code);
}

/** "le fichier est protégé par un mot de passe". */
export function failureCause(i18n: I18n, code: string | null | undefined): string {
  return code && FAILURES.has(code) ? i18n.t(`failure.${code}` as MessageKey) : i18n.t('failure.unknown');
}

/** What to do after a final failure ("Remove the protection..."), or null when "Retry" applies. */
export function failureFix(i18n: I18n, code: string | null | undefined): string | null {
  return code && FIXES.has(code) ? i18n.t(`failure.fix.${code}` as MessageKey) : null;
}

const FORMATS: Record<string, { key: 'pdf' | 'docx' | 'markdown' | 'text'; icon: IconName }> = {
  'application/pdf': { key: 'pdf', icon: 'file' },
  'application/vnd.openxmlformats-officedocument.wordprocessingml.document': { key: 'docx', icon: 'file-text' },
  'text/markdown': { key: 'markdown', icon: 'file-text' },
  'text/plain': { key: 'text', icon: 'file-text' },
};

export function isPdf(mediaType: string): boolean {
  return mediaType === 'application/pdf';
}

/** "PDF", "Word", "Markdown", "Texte". */
export function formatName(i18n: I18n, mediaType: string): string {
  const format = FORMATS[mediaType];
  return format ? i18n.t(`format.${format.key}`) : mediaType;
}

export function FormatIcon({ mediaType }: { mediaType: string }) {
  return <Icon name={FORMATS[mediaType]?.icon ?? 'file'} />;
}

/** The icon of a file before it is imported, from its name. */
export function fileIcon(name: string): IconName {
  return name.toLowerCase().endsWith('.pdf') ? 'file' : 'file-text';
}

/** The extension of a file name, without its dot and in lower case: "pdf". */
export function extensionOf(name: string): string {
  const dot = name.lastIndexOf('.');
  return dot < 0 ? '' : name.slice(dot + 1).toLowerCase();
}

/** ".pdf, .docx, .md" from the extensions sent by the server, "pdf, docx, md". */
export function extensionList(extensions: string | string[]): string {
  const list = Array.isArray(extensions) ? extensions : extensions.split(/,\s*/);
  return list.filter(Boolean).map((extension) => `.${extension}`).join(', ');
}

const UNITS = ['B', 'KB', 'MB', 'GB', 'TB'] as const;

/** "1,2 Mo", in units of 1,024 bytes, as the server counts its limit. */
export function sizeText(i18n: I18n, bytes: number): string {
  let value = bytes;
  let unit = 0;
  while (value >= 1024 && unit < UNITS.length - 1) {
    value /= 1024;
    unit += 1;
  }
  const digits = unit === 0 || value >= 100 ? 0 : 1;
  const n = new Intl.NumberFormat(i18n.locale, { maximumFractionDigits: digits }).format(value);
  return i18n.t(`size.${UNITS[unit]}`, { n });
}

export function languageName(i18n: I18n, language: string | null | undefined): string | null {
  if (language === 'fr' || language === 'en' || language === 'other') return i18n.t(`language.${language}`);
  return null;
}

/** "Indexé : 312 pages" for a PDF, "Indexé : 14 sections" otherwise. */
export function indexedText(i18n: I18n, document: { mediaType: string; pageCount?: number | null; sectionCount?: number | null }): string {
  if (isPdf(document.mediaType) && document.pageCount != null) return i18n.tp('import.indexed.pages', document.pageCount);
  if (document.sectionCount != null) return i18n.tp('import.indexed.sections', document.sectionCount);
  return i18n.t('import.indexed');
}

const STATUS_COLOURS: Record<DocumentStatus, string> = {
  DRAFT: 'var(--st-draft)',
  PUBLISHED: 'var(--st-published)',
  OBSOLETE: 'var(--st-obsolete)',
  ARCHIVED: 'var(--st-archived)',
};

/** The mark of a status: a dashed circle for a draft, a ticked disc once published. */
function StatusIcon({ status }: { status: DocumentStatus }) {
  const colour = STATUS_COLOURS[status];
  return (
    <svg className="st-ico" viewBox="0 0 14 14" aria-hidden="true" focusable="false">
      {status === 'DRAFT' && <circle cx="7" cy="7" r="5.5" fill="none" stroke={colour} strokeWidth="1.5" strokeDasharray="2.3 1.9" />}
      {status === 'PUBLISHED' && (
        <>
          <circle cx="7" cy="7" r="6.25" fill={colour} />
          <path d="m4.4 7.2 1.8 1.8 3.5-3.7" fill="none" stroke="#fff" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" />
        </>
      )}
      {status === 'OBSOLETE' && (
        <>
          <circle cx="7" cy="7" r="5.5" fill="none" stroke={colour} strokeWidth="1.5" />
          <path d="M3.4 10.6 10.6 3.4" stroke={colour} strokeWidth="1.5" />
        </>
      )}
      {status === 'ARCHIVED' && (
        <>
          <circle cx="7" cy="7" r="6.25" fill={colour} />
          <path d="M4.6 7h4.8" stroke="#fff" strokeWidth="1.5" strokeLinecap="round" />
        </>
      )}
    </svg>
  );
}

export function StatusLabel({ status }: { status: string }) {
  const { t } = useI18n();
  if (!(status in STATUS_COLOURS)) return <span className="status">{status}</span>;
  const known = status as DocumentStatus;
  return <span className="status"><StatusIcon status={known} />{t(`docStatus.${known}`)}</span>;
}

export function categoryName(i18n: I18n, category: string | null | undefined): string {
  if (!category) return i18n.t('category.none');
  return category in CATEGORY_PILLS ? i18n.t(`category.${category as Category}`) : category;
}

export function CategoryPill({ category }: { category: string }) {
  const i18n = useI18n();
  const pill = CATEGORY_PILLS[category as Category] ?? 'p-gray';
  return <span className={`pill ${pill}`}>{categoryName(i18n, category)}</span>;
}

/** The state of the extraction, as a coloured label: in progress, indexed, scanned or failed. */
export function IndexPill({ document }: { document: { extractionStatus: string; extractionError?: string | null } }) {
  const i18n = useI18n();
  const { t } = i18n;
  const status = document.extractionStatus as ExtractionStatus;
  if (status === 'PENDING') return <span className="pill p-blue"><Icon name="spinner" className="spin" />{t('extraction.PENDING')}</span>;
  if (status === 'SCANNED') {
    return <span className="pill p-yellow" title={t('extraction.scannedHelp')}><Icon name="scan" />{t('extraction.SCANNED')}</span>;
  }
  if (status === 'FAILED') {
    return (
      <span className="pill p-red" title={failureCause(i18n, document.extractionError)}>
        <Icon name="alert" />{t('extraction.FAILED')}
      </span>
    );
  }
  return <span className="pill p-green"><Icon name="check" />{t('extraction.INDEXED')}</span>;
}

/** The owner of a document; someone who left the organization keeps no name. */
export function Owner({ owner }: { owner?: { id: string; displayName: string } | null }) {
  const { t } = useI18n();
  if (!owner) return <span className="muted">{t('document.formerMember')}</span>;
  return (
    <span className="who">
      <Avatar name={owner.displayName} seed={owner.id} />
      <span>{owner.displayName}</span>
    </span>
  );
}

/** A document by its identifier; one the person cannot see answers 404. */
export function documentQuery(id: string) {
  return {
    queryKey: keys.document(id),
    queryFn: () => call(() => api.GET('/api/v1/documents/{id}', { params: { path: { id } } })),
  };
}

/** The size and formats accepted, for the checks made before sending a file. */
export function useImportLimits() {
  return useQuery({
    queryKey: keys.importLimits,
    queryFn: () => call(() => api.GET('/api/v1/imports/limits')),
  });
}

/**
 * After an import or a change: the lists and counts of the space, the spaces (their number of
 * documents) and the home page are loaded again.
 */
export async function refreshDocuments(spaceId: string): Promise<void> {
  await Promise.all([
    queryClient.invalidateQueries({ queryKey: keys.spaceDocuments(spaceId) }),
    queryClient.invalidateQueries({ queryKey: keys.attention }),
    refreshSpaces(),
  ]);
}

/**
 * After a change of members, roles, folders or spaces: everything shown about documents may
 * have changed (who sees them, who may be chosen, the name of their folder), and is asked again.
 */
export async function refreshAllDocuments(): Promise<void> {
  await Promise.all([
    queryClient.invalidateQueries({ queryKey: keys.allDocuments }),
    queryClient.invalidateQueries({ queryKey: keys.attention }),
  ]);
}

type ListPages = InfiniteData<Schemas['PageResponseDocumentItem'], number>;

/** The refreshes asked during an import, grouped by space: true when documents came or went. */
const grouped = { spaces: new Map<string, boolean>(), timer: undefined as ReturnType<typeof setTimeout> | undefined, last: 0 };

function flush(): void {
  grouped.timer = undefined;
  grouped.last = Date.now();
  const spaces = [...grouped.spaces];
  grouped.spaces.clear();
  for (const [spaceId, documents] of spaces) {
    if (documents) {
      void refreshDocuments(spaceId);
    } else {
      // Only states changed: the counts are asked again, the lines were already updated.
      void queryClient.invalidateQueries({ queryKey: keys.documentSummary(spaceId) });
      void queryClient.invalidateQueries({ queryKey: keys.attention });
    }
  }
}

/**
 * During an import: the screens behind the window follow it, at most once a second. When only
 * the extraction of documents changed, their lines are updated in place instead of loading the
 * whole library again.
 *
 * @param changed the documents whose extraction changed; none when documents came or went
 */
export function refreshSoon(spaceId: string, changed?: DocumentItem[]): void {
  if (changed && changed.length > 0) {
    const byId = new Map(changed.map((item) => [item.id, item]));
    queryClient.setQueriesData<ListPages>({ queryKey: [...keys.spaceDocuments(spaceId), 'list'] }, (data) => data && {
      ...data,
      pages: data.pages.map((page) => ({ ...page, items: page.items.map((item) => byId.get(item.id) ?? item) })),
    });
    for (const id of byId.keys()) void queryClient.invalidateQueries({ queryKey: keys.document(id), exact: true });
  }
  grouped.spaces.set(spaceId, !changed || grouped.spaces.get(spaceId) === true);
  if (grouped.timer !== undefined) return;
  const wait = Math.max(0, grouped.last + timing.group - Date.now());
  if (wait === 0) flush();
  else grouped.timer = setTimeout(flush, wait);
}

/** Opens the "Import documents" window, wherever the person is. */
export function useOpenImport(): () => void {
  const [params, setParams] = useSearchParams();
  return () => {
    if (params.get('open') === 'import') return;
    const next = new URLSearchParams(params);
    next.set('open', 'import');
    // Marked as opened from here: closing it goes back, without leaving a step in the history.
    setParams(next, { state: { importing: true } });
  };
}
