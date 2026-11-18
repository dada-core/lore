import { useInfiniteQuery, useQuery } from '@tanstack/react-query';
import { Fragment, useRef, useState, type KeyboardEvent, type ReactNode } from 'react';
import { Link } from 'react-router';
import { api, call, type Schemas } from '../api/client';
import { keys } from '../api/queryClient';
import { errorMessage } from '../i18n/errors';
import { useI18n, type I18n } from '../i18n/i18n';
import type { MessageKey } from '../i18n/messages';
import { Avatar, copyText } from '../ui/bits';
import { Callout } from '../ui/forms';
import { Icon, Spinner } from '../ui/Icon';
import { useNotify } from '../ui/Toast';
import type { Document, Version } from './documents';

/** The versions shown at first; the others with "All the versions". */
const FIRST = 10;
/** The thickness of a stratum, from a version that changed nothing to one that changed all. */
const THIN = 8;
const THICK = 54;

type ActivityItem = Schemas['ActivityItem'];

/** The thickness of a stratum, from the share of passages its version changed. */
export function thickness(ratio: number | null | undefined): number {
  if (ratio == null) return Math.round((THIN + THICK) / 3);
  return Math.round(THIN + Math.min(1, Math.max(0, ratio)) * (THICK - THIN));
}

/**
 * The history in strata: one band per version, the latest on top and in colour, its thickness the
 * share of the document it changed (hatched when unknown). Below, the version chosen: when and by
 * whom, its note, its file, "Show" for an older one and "Download". The ten latest, then all.
 */
export function Strata({ document }: { document: Document }) {
  const i18n = useI18n();
  const { t } = i18n;
  const [all, setAll] = useState(false);
  const versions = useQuery({
    queryKey: [...keys.documentVersions(document.id), all],
    queryFn: () => call(() => api.GET('/api/v1/documents/{id}/versions', {
      params: { path: { id: document.id }, query: { size: all ? 200 : FIRST } },
    })),
    placeholderData: (previous, previousQuery) => (previousQuery?.queryKey.includes(document.id) ? previous : undefined),
  });
  const group = useRef<HTMLDivElement>(null);
  const [chosen, setChosen] = useState<number | null>(null);
  const items = versions.data?.items ?? [];
  const current = document.currentVersion.number;
  const shown = items.find((version) => version.number === chosen) ?? items.find((version) => version.number === current) ?? items[0];

  // Up and down move along the bands, as in a list.
  const onKey = (event: KeyboardEvent<HTMLButtonElement>, index: number) => {
    const step = event.key === 'ArrowDown' ? 1 : event.key === 'ArrowUp' ? -1 : 0;
    if (!step) return;
    event.preventDefault();
    const next = items[index + step];
    if (!next) return;
    setChosen(next.number);
    (event.currentTarget.parentElement?.children[index + step] as HTMLElement | undefined)?.focus();
  };

  if (versions.isPending) return <div role="status"><Spinner /><span className="sr-only">{t('common.loading')}</span></div>;
  if (versions.isError) return <Callout tone="error">{errorMessage(i18n, versions.error)}</Callout>;
  return (
    <>
      <div className="strata" role="group" aria-label={t('versions.label')} ref={group}>
        {items.map((version, index) => {
          const colour = index === 0 ? 'var(--accent)' : index === items.length - 1 ? 'var(--s2)' : index % 2 ? 'var(--s4)' : 'var(--s3)';
          const pressed = shown?.number === version.number;
          return (
            <button
              key={version.number}
              type="button"
              className="stratum"
              aria-pressed={pressed}
              tabIndex={pressed ? 0 : -1}
              style={{ '--h': `${thickness(version.changeRatio)}px`, '--c': colour } as React.CSSProperties}
              onClick={() => setChosen(version.number)}
              onKeyDown={(event) => onKey(event, index)}
            >
              <span className={version.changeRatio == null ? 'band is-unknown' : 'band'} aria-hidden="true" />
              <span className="stratum-label">
                <span>{t('versions.other', { number: version.number })}</span>
                <span>{stateOf(i18n, version, current)}</span>
              </span>
            </button>
          );
        })}
      </div>
      {shown && <VersionDetail document={document} version={shown} />}
      <p className="caption">{t('versions.caption')}</p>
      {!all && (versions.data?.total ?? 0) > FIRST && (
        <button
          type="button"
          className="link"
          onClick={() => {
            setAll(true);
            group.current?.querySelector<HTMLElement>('[aria-pressed="true"]')?.focus();
          }}
        >
          {t('versions.all', { count: versions.data?.total ?? 0 })}
        </button>
      )}
    </>
  );
}

/** "en cours", "en échec", or the date of the version. */
function stateOf(i18n: I18n, version: Version, current: number): string {
  if (version.number > current && version.extractionStatus === 'PENDING') return i18n.t('versions.inProgress');
  if (version.number > current && version.extractionStatus === 'FAILED') return i18n.t('versions.inFailure');
  return i18n.date(version.createdAt);
}

function VersionDetail({ document, version }: { document: Document; version: Version }) {
  const i18n = useI18n();
  const { t } = i18n;
  const notify = useNotify();
  const current = version.number === document.currentVersion.number;
  const older = version.number < document.currentVersion.number;
  const fileUrl = `/api/v1/documents/${document.id}/versions/${version.number}/file`;
  return (
    <div className="version-detail" aria-live="polite">
      <strong>{current ? t('versions.current', { number: version.number }) : t('versions.other', { number: version.number })}</strong>
      <p>
        {version.author
          ? t('versions.importedBy', { date: i18n.date(version.createdAt), name: version.author.displayName })
          : t('versions.importedOn', { date: i18n.date(version.createdAt) })}
      </p>
      <p>{version.note ?? (version.number === 1 ? t('versions.first') : '')}</p>
      <p className="path">{version.filename}</p>
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
      {version.number > document.currentVersion.number && <p>{t('version.notCurrent')}</p>}
      <div className="actions">
        {older && <Link className="btn" to={`/documents/${document.id}/versions/${version.number}`}>{t('versions.show')}</Link>}
        {version.fileAvailable && <a className="btn" href={fileUrl}><Icon name="download" />{t('versions.download')}</a>}
      </div>
    </div>
  );
}

/** A sentence of the activity: "{name}" in bold, the other values as they come. */
export function rich(template: string, values: Record<string, ReactNode>): ReactNode {
  const parts = template.split(/\{(\w+)\}/);
  return parts.map((part, index) => (index % 2 === 0
    ? <Fragment key={index}>{part}</Fragment>
    : <Fragment key={index}>{values[part] ?? `{${part}}`}</Fragment>));
}

/** Who acted: a name, "Lore" for the system, "Someone" for an account gone. */
function actorName(i18n: I18n, item: { actor?: { displayName?: string | null } | null }): string {
  if (!item.actor) return i18n.t('activity.system');
  return item.actor.displayName ?? i18n.t('activity.someone');
}

/** The sentence of an event of a document, from its action and its details. */
function sentence(i18n: I18n, item: ActivityItem): ReactNode {
  const { t } = i18n;
  const details = (item.details ?? {}) as Record<string, unknown>;
  const name = <b>{actorName(i18n, item)}</b>;
  const status = (value: unknown) => (typeof value === 'string' && ['DRAFT', 'PUBLISHED', 'OBSOLETE', 'ARCHIVED'].includes(value)
    ? t(`docStatus.${value as 'DRAFT'}`) : String(value ?? ''));
  switch (item.action) {
    case 'document.imported':
      return rich(t('activity.document.imported'), { name });
    case 'document.version_imported':
      return rich(t('activity.document.version_imported'), { name, version: String(details.version ?? '') });
    case 'document.status_changed': {
      const replacement = details.replacedBy as { id: string; title: string } | undefined;
      return (
        <>
          {rich(t('activity.document.status_changed'), { name, after: status(details.after) })}
          {replacement && <>. {t('activity.replacedBy')} <Link to={`/documents/${replacement.id}`}><bdi>{replacement.title}</bdi></Link></>}
        </>
      );
    }
    case 'document.updated': {
      const fields = (Array.isArray(details.changes) ? details.changes : [])
        .filter((field): field is string => ['title', 'description', 'category', 'language'].includes(String(field)))
        .map((field) => t(`activity.field.${field as 'title'}`));
      return rich(t('activity.document.updated'), { name, fields: fields.join(', ') });
    }
    case 'document.moved': {
      const to = details.to as { name?: string } | undefined;
      if (!to) return rich(t('activity.document.movedOut'), { name });
      if (!to.name) return rich(t('activity.document.movedGone'), { name });
      return rich(t('activity.document.moved'), { name, folder: to.name });
    }
    case 'document.access_changed': {
      const added = Number(details.added ?? 0);
      if (details.after === 'restricted' && details.before !== 'restricted') {
        return rich(i18n.tp('activity.document.restricted', added), { name, count: String(added) });
      }
      if (details.after === 'space') return rich(t('activity.document.opened'), { name });
      return rich(t('activity.document.access_changed'), { name });
    }
    case 'document.owner_changed':
      return rich(t('activity.document.owner_changed'), { name, to: String(details.toName ?? '') });
    default:
      return rich(t('activity.document.other'), { name, action: item.action });
  }
}

/**
 * What was done to the document, the latest first: imports and versions, statuses, access, person
 * in charge, folder, title, category and language. Never its views nor its downloads.
 */
export function Activity({ document }: { document: Document }) {
  const i18n = useI18n();
  const { t } = i18n;
  const activity = useInfiniteQuery({
    queryKey: keys.documentActivity(document.id),
    queryFn: ({ pageParam }) => call(() => api.GET('/api/v1/documents/{id}/activity', {
      params: { path: { id: document.id }, query: { before: pageParam ?? undefined } },
    })),
    initialPageParam: null as string | null,
    getNextPageParam: (last) => last.next ?? undefined,
  });
  const items = activity.data?.pages.flatMap((page) => page.items) ?? [];
  return (
    <section className="activity" aria-labelledby="document-activity">
      <h2 className="section-title" id="document-activity">{t('activity.title')}</h2>
      {activity.isError && <Callout tone="error">{errorMessage(i18n, activity.error)}</Callout>}
      {activity.isSuccess && items.length === 0 && <p className="muted">{t('activity.none')}</p>}
      <ol>
        {items.map((item) => (
          <li key={item.id}>
            <Avatar name={actorName(i18n, item)} seed={item.actor?.id} />
            <span>{sentence(i18n, item)}<time dateTime={item.occurredAt}>{i18n.relative(item.occurredAt)}</time></span>
          </li>
        ))}
      </ol>
      {activity.hasNextPage && (
        <button type="button" className="btn" disabled={activity.isFetchingNextPage} onClick={() => void activity.fetchNextPage()}>
          {activity.isFetchingNextPage && <Spinner />}
          {t('activity.more')}
        </button>
      )}
    </section>
  );
}

/** The sentence of an event of the home page, with the link of its document. */
export function homeSentence(i18n: I18n, item: Schemas['HomeItem']): ReactNode {
  const details = (item.details ?? {}) as Record<string, unknown>;
  const key = `home.activity.${item.action}` as MessageKey;
  const known = ['document.imported', 'document.version_imported', 'document.status_changed'].includes(item.action);
  const after = typeof details.after === 'string' && ['DRAFT', 'PUBLISHED', 'OBSOLETE', 'ARCHIVED'].includes(details.after)
    ? i18n.t(`docStatus.${details.after as 'DRAFT'}`) : '';
  return rich(i18n.t(known ? key : 'home.activity.document.imported'), {
    name: <b>{actorName(i18n, item)}</b>,
    title: <Link to={`/documents/${item.document.id}`}><bdi>{item.document.title}</bdi></Link>,
    version: String(details.version ?? ''),
    after,
  });
}
