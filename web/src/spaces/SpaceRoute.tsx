import { useQuery } from '@tanstack/react-query';
import { Outlet, useParams } from 'react-router';
import { ApiError } from '../api/client';
import { errorMessage } from '../i18n/errors';
import { useI18n } from '../i18n/i18n';
import { View, type Crumb } from '../layouts/AppShell';
import { NotFoundPage } from '../pages/NotFoundPage';
import { Callout } from '../ui/forms';
import { Spinner } from '../ui/Icon';
import { spaceBySlug, type Space } from './spaces';

/**
 * Loads the space of the address (/spaces/{slug}) for the pages below it. A space the person
 * cannot see shows the same page as a missing one.
 */
export function SpaceRoute() {
  const i18n = useI18n();
  const { slug = '' } = useParams();
  const space = useQuery(spaceBySlug(slug));

  if (space.error instanceof ApiError && space.error.status === 404) return <NotFoundPage />;
  // Only a first load shows its error: when a reload fails, the space stays on screen.
  if (!space.data) {
    return (
      <View crumbs={[{ label: i18n.t('nav.spaces'), to: '/spaces' }, { label: slug }]}>
        <div className="pad">
          {space.isError
            ? <Callout tone="error" role="alert">{errorMessage(i18n, space.error)}</Callout>
            : <div className="grid-empty" role="status"><Spinner /><span className="sr-only">{i18n.t('common.loading')}</span></div>}
        </div>
      </View>
    );
  }
  return <Outlet context={space.data} />;
}

/** Spaces › Infrastructure › Members. */
export function spaceCrumbs(label: string, space: Space, page?: string): Crumb[] {
  const crumbs: Crumb[] = [{ label, to: '/spaces' }, { label: space.name, to: `/spaces/${space.slug}` }];
  if (page) crumbs.push({ label: page });
  return crumbs;
}
