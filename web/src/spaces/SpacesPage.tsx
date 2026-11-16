import { Link, useSearchParams } from 'react-router';
import { errorMessage } from '../i18n/errors';
import { useI18n } from '../i18n/i18n';
import { View } from '../layouts/AppShell';
import { HorizontalScroll, useDocumentTitle } from '../ui/bits';
import { Callout } from '../ui/forms';
import { Icon, Spinner } from '../ui/Icon';
import { SpaceIcon, SpaceRolePill, useCreateSpace, useSpaces } from './spaces';

/** All the spaces the person sees: the active ones, or the archived ones. */
export function SpacesPage() {
  const i18n = useI18n();
  const { t } = i18n;
  const [params] = useSearchParams();
  const archived = params.get('archived') === 'true';
  const spaces = useSpaces(archived);
  const createSpace = useCreateSpace();
  useDocumentTitle(t('nav.spaces'));
  const rows = spaces.data ?? [];

  return (
    <View
      crumbs={[{ label: t('nav.spaces') }]}
      actions={(
        <button type="button" className="btn btn-primary" onClick={createSpace}>
          <Icon name="plus" />
          <span className="btn-label">{t('nav.createSpace')}</span>
        </button>
      )}
    >
      <div className="pad">
        <div className="stack">
          <h1 className="page-title">{t('nav.spaces')}</h1>
          <nav className="views" aria-label={t('spaces.views')}>
            <Link className="view-tab" to="/spaces" aria-current={archived ? undefined : 'page'}>{t('spaces.active')}</Link>
            <Link className="view-tab" to="/spaces?archived=true" aria-current={archived ? 'page' : undefined}>
              <Icon name="archive" />{t('spaces.archived')}
            </Link>
          </nav>
          {spaces.isError && <Callout tone="error" role="alert">{errorMessage(i18n, spaces.error)}</Callout>}
          <div className="set-box is-flush">
            <HorizontalScroll stickyFirstColumn>
              <table className="grid" style={{ minWidth: 760 }}>
                <thead>
                  <tr>
                    <th className="rn"><span className="sr-only">#</span></th>
                    <th className="primary"><span className="th"><Icon name="grid" />{t('spaces.col.name')}</span></th>
                    <th><span className="th"><Icon name="file-text" />{t('spaces.col.description')}</span></th>
                    <th><span className="th"><Icon name="users" />{t('spaces.col.members')}</span></th>
                    <th><span className="th"><Icon name="tag" />{t('spaces.col.role')}</span></th>
                    <th><span className="th"><Icon name="clock" />{t('spaces.col.activity')}</span></th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((space, index) => (
                    <tr key={space.id}>
                      <td className="rn">{index + 1}</td>
                      <td className="primary">
                        <Link className="who" to={`/spaces/${space.slug}`}>
                          <SpaceIcon space={space} />
                          <span>{space.name}</span>
                        </Link>
                      </td>
                      <td style={{ maxWidth: 320 }} title={space.description ?? undefined}>
                        {space.description ?? <span className="muted">{t('spaces.noDescription')}</span>}
                      </td>
                      <td className="num">{space.memberCount}</td>
                      <td><SpaceRolePill role={space.role} /></td>
                      <td>{i18n.relative(space.lastActivityAt)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </HorizontalScroll>
            {spaces.isPending && <div className="grid-empty" role="status"><Spinner /><span className="sr-only">{t('common.loading')}</span></div>}
            {spaces.isSuccess && rows.length === 0 && (
              <p className="grid-empty">{t(archived ? 'spaces.noneArchived' : 'spaces.none')}</p>
            )}
            {rows.length > 0 && <div className="grid-foot"><span>{i18n.tp('spaces.count', rows.length)}</span></div>}
          </div>
        </div>
      </div>
    </View>
  );
}
