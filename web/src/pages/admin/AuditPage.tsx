import { useInfiniteQuery, useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { useSearchParams } from 'react-router';
import { api, call, type Schemas } from '../../api/client';
import { keys } from '../../api/queryClient';
import { errorMessage } from '../../i18n/errors';
import { useI18n, type I18n } from '../../i18n/i18n';
import { messages, type MessageKey } from '../../i18n/messages';
import { SettingsView } from '../../layouts/SettingsView';
import { Avatar, HorizontalScroll } from '../../ui/bits';
import { Callout } from '../../ui/forms';
import { Icon, Spinner } from '../../ui/Icon';
import { Menu, MenuItem, MenuSeparator } from '../../ui/Menu';

type AuditItem = Schemas['AuditItem'];

/** The categories of the log, in the order of the filter, and the colour of each. */
const CATEGORIES = {
  view: 'p-gray',
  download: 'p-cyan',
  import: 'p-blue',
  status: 'p-yellow',
  change: 'p-teal',
  deletion: 'p-red',
  rights: 'p-purple',
  members: 'p-green',
  sign_in: 'p-orange',
  organization: 'p-gray',
  other: 'p-gray',
} as const;
type Category = keyof typeof CATEGORIES;

/** The periods of the filter, in days. */
const PERIODS = { '7d': 7, '30d': 30, '12m': 365 } as const;
type Period = keyof typeof PERIODS;

const STATUSES = ['DRAFT', 'PUBLISHED', 'OBSOLETE', 'ARCHIVED'];

/** The start of a period, from now. */
function since(period: Period): string {
  return new Date(Date.now() - PERIODS[period] * 86_400_000).toISOString();
}

function isCategory(value: string | null): value is Category {
  return value !== null && Object.hasOwn(CATEGORIES, value);
}

function isKey(key: string): key is MessageKey {
  return Object.hasOwn(messages.en, key);
}

/**
 * The sentence of an event, written here from its action and its details, in the language of the
 * person reading; an action this version does not know shows its code.
 */
export function auditSentence(i18n: I18n, item: AuditItem): string {
  const details = (item.details ?? {}) as Record<string, unknown>;
  const key = `audit.action.${item.action}`;
  if (!isKey(key)) return item.action;
  const params: Record<string, string> = {};
  for (const [name, value] of Object.entries(details)) {
    if (typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean') params[name] = String(value);
  }
  if (item.action === 'document.status_changed' && STATUSES.includes(params.after ?? '')) {
    params.after = i18n.t(`docStatus.${params.after as 'DRAFT'}`);
  }
  for (const name of ['title', 'space', 'name', 'previousName', 'email', 'toName', 'version', 'minutes', 'after', 'rows']) {
    params[name] ??= '';
  }
  return i18n.t(key, params);
}

/**
 * Administration › Audit log: the operations of the organization, by category, person and period,
 * the latest first, fifty at a time; their export in CSV with the same filters.
 */
export function AuditPage() {
  const i18n = useI18n();
  const { t } = i18n;
  const [params, setParams] = useSearchParams();
  const [showIp, setShowIp] = useState(false);
  const category = isCategory(params.get('category')) ? params.get('category') as Category : null;
  const actor = params.get('actor');
  const period: Period = Object.hasOwn(PERIODS, params.get('period') ?? '') ? params.get('period') as Period : '7d';
  const filters = { category: category ?? undefined, actor: actor ?? undefined };

  const members = useQuery({
    queryKey: [...keys.allMembers, 'audit'],
    queryFn: () => call(() => api.GET('/api/v1/admin/members', { params: { query: { size: 200 } } })),
  });
  const events = useInfiniteQuery({
    queryKey: [...keys.audit, category, actor, period],
    // The cursor carries the pages on: the start of the period may move by the time one is asked.
    queryFn: ({ pageParam }) => call(() => api.GET('/api/v1/admin/audit', {
      params: { query: { ...filters, from: since(period), before: pageParam ?? undefined } },
    })),
    initialPageParam: null as string | null,
    getNextPageParam: (last) => last.next ?? undefined,
  });
  const items = events.data?.pages.flatMap((page) => page.items) ?? [];

  const choose = (name: string, value: string | null) => {
    const next = new URLSearchParams(params);
    if (value) next.set(name, value);
    else next.delete(name);
    setParams(next, { replace: true });
  };
  // The file comes as an attachment: the page stays.
  const exportLog = () => {
    const query = new URLSearchParams(Object.entries({ ...filters, from: since(period) })
      .filter((entry): entry is [string, string] => entry[1] !== undefined));
    // One export at a time: the button waits a little, so that a second click asks nothing.
    setExporting(true);
    window.setTimeout(() => setExporting(false), 5000);
    window.location.assign(`/api/v1/admin/audit/export?${query.toString()}`);
  };
  const dateTime = new Intl.DateTimeFormat(i18n.locale, { dateStyle: 'medium', timeStyle: 'short', timeZone: i18n.timeZone });
  const people = members.data?.items ?? [];
  // A person chosen who is no longer a member: still filtered, named as a former account.
  const actorName = !actor ? null : actor === 'system' ? t('audit.system')
    : people.find((person) => person.id === actor)?.displayName ?? t('audit.formerMember');
  const [exporting, setExporting] = useState(false);

  return (
    <SettingsView
      section="audit"
      title={t('admin.audit')}
      wide
      actions={(
        <button type="button" className="btn" disabled={exporting} onClick={exportLog}>
          {exporting ? <Spinner /> : <Icon name="download" />}{t('audit.export')}
        </button>
      )}
    >
      <div className="lib-tools" style={{ marginBottom: 10 }}>
        <Menu
          triggerClassName={category ? 'tool is-on-blue' : 'tool'}
          label={t('audit.category')}
          trigger={<><Icon name="tag" />{category ? t(`audit.category.${category}`) : t('audit.allCategories')}</>}
        >
          <MenuItem checked={!category} onSelect={() => choose('category', null)}>{t('audit.allCategories')}</MenuItem>
          <MenuSeparator />
          {(Object.keys(CATEGORIES) as Category[]).map((value) => (
            <MenuItem key={value} checked={category === value} onSelect={() => choose('category', value)}>
              {t(`audit.category.${value}`)}
            </MenuItem>
          ))}
        </Menu>
        <Menu
          triggerClassName={actor ? 'tool is-on-blue' : 'tool'}
          label={t('audit.person')}
          trigger={<><Icon name="user" />{actorName ?? t('audit.everyone')}</>}
        >
          <MenuItem checked={!actor} onSelect={() => choose('actor', null)}>{t('audit.everyone')}</MenuItem>
          <MenuItem checked={actor === 'system'} onSelect={() => choose('actor', 'system')}>{t('audit.system')}</MenuItem>
          <MenuSeparator />
          {people.map((person) => (
            <MenuItem key={person.id} checked={actor === person.id} onSelect={() => choose('actor', person.id)}>
              {person.displayName}
            </MenuItem>
          ))}
        </Menu>
        <Menu
          triggerClassName={period !== '7d' ? 'tool is-on-blue' : 'tool'}
          label={t('audit.period')}
          trigger={<><Icon name="calendar" />{t(`audit.period.${period}`)}</>}
        >
          {(Object.keys(PERIODS) as Period[]).map((value) => (
            <MenuItem key={value} checked={period === value} onSelect={() => choose('period', value === '7d' ? null : value)}>
              {t(`audit.period.${value}`)}
            </MenuItem>
          ))}
        </Menu>
        <label className="check">
          <input type="checkbox" checked={showIp} onChange={(event) => setShowIp(event.target.checked)} />
          {t('audit.showIp')}
        </label>
      </div>

      {events.isError && <Callout tone="error" role="alert">{errorMessage(i18n, events.error)}</Callout>}
      {events.isPending && <div className="grid-empty" role="status"><Spinner /><span className="sr-only">{t('common.loading')}</span></div>}
      {events.isSuccess && (
        <div className="set-box is-flush">
          <HorizontalScroll>
            <table className="grid" style={{ minWidth: 760 }}>
              <thead>
                <tr>
                  <th><span className="th"><Icon name="calendar" />{t('audit.col.date')}</span></th>
                  <th><span className="th"><Icon name="user" />{t('audit.col.person')}</span></th>
                  <th><span className="th"><Icon name="tag" />{t('audit.col.category')}</span></th>
                  <th><span className="th"><Icon name="text" />{t('audit.col.event')}</span></th>
                  {showIp && <th><span className="th"><Icon name="globe" />{t('audit.col.ip')}</span></th>}
                </tr>
              </thead>
              <tbody>
                {items.map((item) => {
                  const known = isCategory(item.category) ? item.category : 'other';
                  const name = item.actor ? item.actor.displayName ?? t('audit.formerMember') : t('audit.system');
                  return (
                    <tr key={item.id}>
                      <td><time dateTime={item.occurredAt}>{dateTime.format(new Date(item.occurredAt))}</time></td>
                      <td>
                        {item.actor ? (
                          <span className="owner"><Avatar name={name} seed={item.actor.id} /><span>{name}</span></span>
                        ) : <span className="muted">{name}</span>}
                      </td>
                      <td><span className={`pill ${CATEGORIES[known]}`}>{t(`audit.category.${known}`)}</span></td>
                      <td><bdi>{auditSentence(i18n, item)}</bdi></td>
                      {showIp && <td className="mono">{item.ip ?? ''}</td>}
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </HorizontalScroll>
          {items.length === 0 && <p className="grid-empty">{t('audit.empty')}</p>}
          <div className="grid-foot">
            <span>{t('audit.footer')}</span>
            {events.hasNextPage && (
              <span className="pager">
                <button type="button" className="btn" disabled={events.isFetchingNextPage} onClick={() => void events.fetchNextPage()}>
                  {events.isFetchingNextPage && <Spinner />}
                  {t('audit.more')}
                </button>
              </span>
            )}
          </div>
        </div>
      )}
    </SettingsView>
  );
}
