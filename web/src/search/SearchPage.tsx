import { useInfiniteQuery } from '@tanstack/react-query';
import { useEffect, useId, useRef, useState, type FormEvent } from 'react';
import { Link, useLocation, useNavigate, useSearchParams } from 'react-router';
import { api, ApiError, call } from '../api/client';
import { categoryName, CategoryPill, FormatIcon, formatName, StatusLabel } from '../documents/documents';
import { errorMessage, fieldMessage } from '../i18n/errors';
import { useI18n } from '../i18n/i18n';
import { View } from '../layouts/AppShell';
import { useUser } from '../session/session';
import { Callout } from '../ui/forms';
import { Icon, Spinner } from '../ui/Icon';
import { Menu, MenuCheck, MenuItem } from '../ui/Menu';
import { useDocumentTitle } from '../ui/bits';
import { useNotify } from '../ui/Toast';
import {
  filtersOf,
  forgetSearches,
  hasFilters,
  Highlighted,
  passageLink,
  placeLabel,
  recentSearches,
  rememberSearch,
  searchAddress,
  type SearchFilters,
  type SearchPage as Page,
  type SearchResult,
} from './search';

/** The search page: without a query, the recent searches and the syntax; with one, the results. */
export function SearchPage() {
  const i18n = useI18n();
  const { t } = i18n;
  const user = useUser();
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const location = useLocation();
  const query = (params.get('q') ?? '').trim();
  const filters = filtersOf(params);
  const [typed, setTyped] = useState(query);
  const field = useRef<HTMLInputElement>(null);
  const fieldId = useId();
  const errorId = useId();
  useDocumentTitle(query ? t('search.docTitle', { query }) : t('search.title'));

  // A new query in the address (a recent search, the palette, the history): the field follows.
  const [shown, setShown] = useState(query);
  if (shown !== query) {
    setShown(query);
    setTyped(query);
  }
  // "/" opens this page with its field selected: once the new view has put the focus on its title.
  useEffect(() => {
    if (!(location.state as { focus?: boolean } | null)?.focus) return undefined;
    const frame = requestAnimationFrame(() => field.current?.focus());
    return () => cancelAnimationFrame(frame);
  }, [location.state]);

  const results = useInfiniteQuery({
    queryKey: ['search', query, filters],
    queryFn: ({ pageParam, signal }) => call(() => api.GET('/api/v1/search', {
      params: {
        query: {
          q: query,
          space: filters.spaces.length ? filters.spaces : undefined,
          category: filters.categories.length ? filters.categories : undefined,
          status: filters.statuses.length ? filters.statuses : undefined,
          format: filters.formats.length ? filters.formats : undefined,
          updated: filters.updated || undefined,
          includeArchived: filters.includeArchived || undefined,
          page: pageParam,
        },
      },
      signal,
    })),
    initialPageParam: 0,
    // 1,000 documents at most: beyond, a more precise search is better.
    getNextPageParam: (last: Page) => ((last.page + 1) * last.size < Math.min(last.total, 1000) ? last.page + 1 : undefined),
    enabled: query !== '',
    staleTime: 60_000,
    // A search that took too long is not asked again on its own: the person makes it more precise.
    retry: false,
  });

  useEffect(() => {
    if (query && results.isSuccess) rememberSearch(user.id, query);
  }, [query, results.isSuccess, user.id]);

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const text = typed.trim();
    if (!text) return;
    // A new query starts without filters.
    navigate(searchAddress(text));
  };

  const error = results.error instanceof ApiError ? results.error : null;
  const fieldError = error?.fieldErrors.find((item) => item.field === 'q');

  const first = results.data?.pages[0];
  const announced = !query || !first ? '' : first.total === 0 && !hasFilters(filters)
    ? t('search.none', { query })
    : i18n.tp('search.count', first.total);

  return (
    <View crumbs={[{ label: t('search.title') }]}>
      <div className="sr-only" aria-live="polite">{announced}</div>
      <div className="search-head">
        <h1 className="sr-only">{t('search.title')}</h1>
        <form className="search-box" role="search" onSubmit={submit} aria-label={t('search.title')}>
          <Icon name="search" />
          <label className="sr-only" htmlFor={fieldId}>{t('search.field')}</label>
          <input
            id={fieldId}
            ref={field}
            type="search"
            name="q"
            value={typed}
            maxLength={500}
            placeholder={t('search.field')}
            autoComplete="off"
            aria-invalid={fieldError ? true : undefined}
            aria-describedby={fieldError ? errorId : undefined}
            onChange={(event) => setTyped(event.target.value)}
          />
          <button className="btn btn-primary" type="submit">{t('search.submit')}</button>
        </form>
        {fieldError && <p className="field-error" id={errorId}>{fieldMessage(i18n, fieldError)}</p>}
        {query && results.data && results.data.pages[0].total + results.data.pages[0].facets.spaces.length > 0 && (
          <Filters query={query} filters={filters} page={results.data.pages[0]} />
        )}
      </div>
      <div className="results">
        {!query && <Start userId={user.id} />}
        {query && results.isPending && (
          <div className="grid-empty" role="status"><Spinner /><span className="sr-only">{t('search.loading')}</span></div>
        )}
        {query && error && !fieldError && <Callout tone="error" role="alert">{errorMessage(i18n, error)}</Callout>}
        {query && results.data && (
          <Results
            query={query}
            filters={filters}
            pages={results.data.pages}
            more={results.hasNextPage}
            loadingMore={results.isFetchingNextPage}
            onMore={() => void results.fetchNextPage()}
          />
        )}
      </div>
    </View>
  );
}

/** Without a query: the recent searches of this browser, and the syntax. */
function Start({ userId }: { userId: string }) {
  const { t } = useI18n();
  const notify = useNotify();
  const [recent, setRecent] = useState(() => recentSearches(userId));
  return (
    <div className="search-start">
      {recent.length > 0 && (
        <section aria-labelledby="recent-searches">
          <div className="row-between">
            <h2 className="section-title" id="recent-searches">{t('search.recent')}</h2>
            <button
              type="button"
              className="link"
              onClick={() => {
                forgetSearches(userId);
                setRecent([]);
                notify(t('search.recentCleared'));
              }}
            >
              {t('search.clearRecent')}
            </button>
          </div>
          <ul className="list">
            {recent.map((item) => (
              <li key={item}>
                <Link className="list-row" to={searchAddress(item)}>
                  <Icon name="clock" />
                  <span className="list-main"><span className="list-title mono">{item}</span></span>
                  <Icon name="chevron-right" />
                </Link>
              </li>
            ))}
          </ul>
        </section>
      )}
      <section aria-labelledby="search-syntax">
        <h2 className="section-title" id="search-syntax">{t('search.syntax')}</h2>
        <dl className="syntax">
          <dt><code>"does not exist"</code></dt><dd>{t('search.syntax.phrase')}</dd>
          <dt><code>-barman</code></dt><dd>{t('search.syntax.exclude')}</dd>
          <dt><code>pg_restore or pg_dump</code></dt><dd>{t('search.syntax.or')}</dd>
          <dt><code>ERR_CONNECTION_REFUSED</code></dt><dd>{t('search.syntax.ids')}</dd>
          <dt><code>role</code></dt><dd>{t('search.syntax.accents')}</dd>
        </dl>
      </section>
    </div>
  );
}

/** The chips of the filters: each value present in the results, with its number of documents. */
function Filters({ query, filters, page }: { query: string; filters: SearchFilters; page: Page }) {
  const i18n = useI18n();
  const { t } = i18n;
  const navigate = useNavigate();
  const go = (next: SearchFilters) => navigate(searchAddress(query, next), { replace: true });
  const { facets } = page;

  // The spaces: the active ones are kept unless left out; an archived one only when chosen.
  const spaceIds = facets.spaces.map((space) => space.id);
  const usual = facets.spaces.filter((space) => !space.archived).map((space) => space.id);
  const keptSpaces = filters.spaces.length ? filters.spaces.filter((id) => spaceIds.includes(id)) : usual;
  const toggleSpace = (id: string) => {
    const next = keptSpaces.includes(id) ? keptSpaces.filter((item) => item !== id) : [...keptSpaces, id];
    if (next.length === 0) return;
    const same = next.length === usual.length && usual.every((item) => next.includes(item));
    go({ ...filters, spaces: same ? [] : next, includeArchived: false });
  };

  const chip = (
    key: 'categories' | 'statuses' | 'formats',
    label: string,
    values: { value: string; count: number }[],
    name: (value: string) => string,
  ) => {
    const all = values.map((value) => value.value);
    const kept = filters[key].length ? filters[key].filter((value) => all.includes(value)) : all;
    const toggle = (value: string) => {
      const next = kept.includes(value) ? kept.filter((item) => item !== value) : [...kept, value];
      if (next.length === 0) return;
      go({ ...filters, [key]: next.length === all.length ? [] : next });
    };
    const some = kept.length < all.length;
    return (
      <Menu
        key={key}
        triggerClassName={some ? 'chip-btn is-set' : 'chip-btn'}
        trigger={<>{some ? t('search.facet.some', { label, count: kept.length, total: all.length }) : label}<Icon name="chevron" /></>}
      >
        {values.map((value) => (
          <MenuCheck
            key={value.value}
            checked={kept.includes(value.value)}
            disabled={kept.length === 1 && kept.includes(value.value)}
            onToggle={() => toggle(value.value)}
          >
            <span>{name(value.value)}</span>
            <span className="count">{value.count}</span>
          </MenuCheck>
        ))}
      </Menu>
    );
  };

  const someSpaces = keptSpaces.length !== usual.length || keptSpaces.some((id) => !usual.includes(id));
  const dates = [
    ['', t('search.date.any')],
    ['30d', t('search.date.30d')],
    ['12m', t('search.date.12m')],
  ] as const;
  return (
    <div className="chips" aria-label={t('search.filters')} role="group">
      <Menu
        triggerClassName={someSpaces ? 'chip-btn is-set' : 'chip-btn'}
        trigger={<>{someSpaces
          ? t('search.facet.some', { label: t('search.facet.space'), count: keptSpaces.length, total: facets.spaces.length })
          : t('search.facet.space')}<Icon name="chevron" /></>}
      >
        {facets.spaces.map((space) => (
          <MenuCheck
            key={space.id}
            checked={keptSpaces.includes(space.id)}
            disabled={keptSpaces.length === 1 && keptSpaces.includes(space.id)}
            onToggle={() => toggleSpace(space.id)}
          >
            <span>{space.name}{space.archived && <span className="muted"> · {t('search.archived')}</span>}</span>
            <span className="count">{space.count}</span>
          </MenuCheck>
        ))}
      </Menu>
      {chip('categories', t('search.facet.category'), facets.categories, (value) => categoryName(i18n, value))}
      {chip('statuses', t('search.facet.status'), facets.statuses, (value) => t(`docStatus.${value as 'DRAFT'}`))}
      {chip('formats', t('search.facet.format'), facets.formats, (value) => formatName(i18n, value))}
      <Menu
        triggerClassName={filters.updated ? 'chip-btn is-set' : 'chip-btn'}
        trigger={<>{filters.updated ? t(`search.date.${filters.updated}`) : t('search.facet.date')}<Icon name="chevron" /></>}
      >
        {dates.map(([value, label]) => (
          <MenuItem key={value} checked={filters.updated === value} onSelect={() => go({ ...filters, updated: value })}>
            {label}
          </MenuItem>
        ))}
      </Menu>
    </div>
  );
}

function Results({ query, filters, pages, more, loadingMore, onMore }: {
  query: string;
  filters: SearchFilters;
  pages: Page[];
  more: boolean;
  loadingMore: boolean;
  onMore: () => void;
}) {
  const { t, tp } = useI18n();
  const navigate = useNavigate();
  const first = pages[0];
  const items = pages.flatMap((page) => page.items);
  // Without filters, the documents found in the active spaces: "2 documents of 3".
  const unfiltered = first.facets.spaces.filter((space) => !space.archived).reduce((sum, space) => sum + space.count, 0);

  if (first.total === 0 && !hasFilters(filters)) {
    return (
      <div className="empty">
        <Icon name="search" />
        <h2>{t('search.none', { query })}</h2>
        <p>{t('search.noneHelp')}</p>
      </div>
    );
  }
  return (
    <>
      <div className="results-meta">
        <span>
          {first.total < unfiltered
            ? tp('search.countOf', first.total, { total: unfiltered })
            : tp('search.count', first.total)}
        </span>
        <span>{t('search.order')}</span>
      </div>
      {first.total === 0 ? (
        <div className="row-between results-none">
          <p className="help">{t('search.noneFiltered')}</p>
          <button type="button" className="btn" onClick={() => navigate(searchAddress(query), { replace: true })}>
            {t('search.clearFilters')}
          </button>
        </div>
      ) : (
        items.map((result) => <ResultItem key={result.document.id} query={query} result={result} />)
      )}
      {more && (
        <div className="results-more">
          <button type="button" className="btn" disabled={loadingMore} onClick={onMore}>
            {loadingMore && <Spinner />}
            {t('search.more')}
          </button>
        </div>
      )}
      <ul className="notes">
        <li><Icon name="lock" />{t('search.onlyVisible')}</li>
        <li><Icon name="scan" />{t('search.scannedNote')}</li>
      </ul>
    </>
  );
}

function ResultItem({ query, result }: { query: string; result: SearchResult }) {
  const i18n = useI18n();
  const { t, tp } = i18n;
  const location = useLocation();
  const [open, setOpen] = useState(false);
  const othersId = useId();
  const { document, passage } = result;
  const state = { fromSearch: { words: result.words, results: `${location.pathname}${location.search}` } };
  const link = passageLink(document.id, query, result.version, passage?.anchor);
  const obsolete = document.status === 'OBSOLETE';
  const lang = document.language === 'en' || document.language === 'fr' ? document.language : undefined;
  return (
    <article className={obsolete ? 'result is-obsolete' : 'result'} aria-labelledby={`result-${document.id}`}>
      <FormatIcon mediaType={document.mediaType} />
      <div className="result-body">
        <Link className="result-title" id={`result-${document.id}`} to={link} state={state} lang={lang}>
          <bdi><Highlighted text={document.title} ranges={document.titleHighlights} /></bdi>
        </Link>
        <div className="result-where">
          <span>{document.space.name} › {document.folder?.name ?? t('library.noFolder')}</span>
          {passage && <span><bdi>{placeLabel(i18n, passage)}</bdi></span>}
          {document.category && <CategoryPill category={document.category} />}
        </div>
        {passage ? (
          <p className="excerpt" lang={lang}><bdi><Highlighted text={passage.excerpt} ranges={passage.highlights} /></bdi></p>
        ) : result.description ? (
          <p className="excerpt" lang={lang}><bdi><Highlighted text={result.description.text} ranges={result.description.highlights} /></bdi></p>
        ) : (
          <p className="excerpt excerpt-meta">
            {document.extractionStatus === 'SCANNED' ? t('search.matchScanned') : t('search.matchTitle')}
          </p>
        )}
        <div className="result-meta">
          <StatusLabel status={document.status} />
          <span>{t('document.version', { number: result.version })}</span>
          <span>{t('document.updated', { when: i18n.relative(document.updatedAt) })}</span>
          {document.restricted && <span className="status"><Icon name="lock" className="lock" />{t('library.restricted')}</span>}
        </div>
        {result.otherPassages.total > 0 && (
          <div className="more">
            <button type="button" className="link" aria-expanded={open} aria-controls={othersId} onClick={() => setOpen(!open)}>
              {tp('search.others', result.otherPassages.total)}
            </button>
            <ul id={othersId} hidden={!open}>
              {result.otherPassages.items.map((other) => (
                <li key={other.anchor}>
                  <Link to={passageLink(document.id, query, result.version, other.anchor)} state={state}>
                    <bdi>{placeLabel(i18n, other)}</bdi>
                  </Link>
                </li>
              ))}
            </ul>
          </div>
        )}
      </div>
    </article>
  );
}

