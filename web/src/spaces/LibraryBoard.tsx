import { useInfiniteQuery } from '@tanstack/react-query';
import { Link } from 'react-router';
import { api, call } from '../api/client';
import { keys } from '../api/queryClient';
import { CategoryPill, IndexPill, Owner, STATUSES, StatusLabel, whileExtracting, type DocumentStatus } from '../documents/documents';
import { errorMessage } from '../i18n/errors';
import { useI18n } from '../i18n/i18n';
import { HorizontalScroll } from '../ui/bits';
import { Callout } from '../ui/forms';
import { Icon, Spinner } from '../ui/Icon';
import type { Space } from './spaces';

/** The cards of a column, at first and each time "Show more" is chosen. */
const COLUMN = 50;

/**
 * The library by status: a column per status, Archived for the maintainers of the space only, a
 * card per document. No dragging: making a document obsolete asks for the one that replaces it,
 * which the window "Change the status" does.
 */
export function LibraryBoard({ space, folder, archived }: {
  space: Space;
  folder: string | null;
  /** The person sees the archived documents: a maintainer of the space, an administrator. */
  archived: boolean;
}) {
  const statuses = STATUSES.filter((status) => status !== 'ARCHIVED' || archived);
  return (
    <HorizontalScroll>
      <div className="board">
        {statuses.map((status) => <Column key={status} space={space} folder={folder} status={status} />)}
      </div>
    </HorizontalScroll>
  );
}

function Column({ space, folder, status }: { space: Space; folder: string | null; status: DocumentStatus }) {
  const i18n = useI18n();
  const { t } = i18n;
  const documents = useInfiniteQuery({
    queryKey: [...keys.documents(space.id, folder ?? 'all'), 'status', status],
    queryFn: ({ pageParam }) => call(() => api.GET('/api/v1/spaces/{spaceId}/documents', {
      params: {
        path: { spaceId: space.id },
        query: { folder: folder ?? undefined, status: [status], sort: 'updated', direction: 'desc', page: pageParam, size: COLUMN },
      },
    })),
    initialPageParam: 0,
    getNextPageParam: (last) => ((last.page + 1) * last.size < last.total ? last.page + 1 : undefined),
    refetchInterval: whileExtracting((data) => data.pages.some((page) => page.items.some((item) => item.extractionStatus === 'PENDING'
      || item.newerVersion?.extractionStatus === 'PENDING'))),
  });
  const items = documents.data?.pages.flatMap((page) => page.items) ?? [];
  const total = documents.data?.pages[0]?.total ?? 0;
  const headId = `column-${status}`;
  return (
    <section className="column" aria-labelledby={headId}>
      <h2 className="column-head" id={headId}><StatusLabel status={status} /><span className="count">{total}</span></h2>
      {documents.isPending && <div role="status"><Spinner /><span className="sr-only">{t('common.loading')}</span></div>}
      {documents.isError && <Callout tone="error">{errorMessage(i18n, documents.error)}</Callout>}
      <div className="cards">
        {items.map((item) => (
          <Link key={item.id} className="card" to={`/documents/${item.id}`}>
            <div className="card-title">
              <bdi>{item.title}</bdi>
              {item.restricted && (
                <>
                  {' '}
                  <span role="img" aria-label={t('library.restricted')} title={t('library.restricted')}><Icon name="lock" className="lock" /></span>
                </>
              )}
            </div>
            {(item.category || item.extractionStatus !== 'INDEXED') && (
              <div className="card-pills">
                {item.category && <CategoryPill category={item.category} />}
                {item.extractionStatus !== 'INDEXED' && <IndexPill document={item} />}
              </div>
            )}
            <div className="card-meta">
              <Owner owner={item.owner} />
              <span>{t('library.cardVersion', { number: item.version, when: i18n.relative(item.updatedAt) })}</span>
            </div>
          </Link>
        ))}
        {documents.isSuccess && items.length === 0 && <p className="column-empty">{t('library.columnEmpty')}</p>}
      </div>
      {documents.hasNextPage && (
        <button type="button" className="btn" disabled={documents.isFetchingNextPage} onClick={() => void documents.fetchNextPage()}>
          {documents.isFetchingNextPage && <Spinner />}
          {t('library.columnMore')}
        </button>
      )}
    </section>
  );
}
