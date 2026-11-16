import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router';
import { api, call, type Schemas } from '../api/client';
import { keys } from '../api/queryClient';
import { failureCause, useOpenImport, whileExtracting } from '../documents/documents';
import { useI18n } from '../i18n/i18n';
import { View } from '../layouts/AppShell';
import { useUser } from '../session/session';
import { canContribute, SpaceIcon, useCreateSpace, useSpaces, type SpaceRole } from '../spaces/spaces';
import { useDocumentTitle } from '../ui/bits';
import { Icon, Spinner, type IconName } from '../ui/Icon';

type AttentionItem = Schemas['AttentionItem'];

/**
 * Home. It fills up as Lore grows: the spaces of the person and what their imports still need,
 * then the recent documents. And where to start, and the keyboard shortcuts.
 */
export function HomePage() {
  const i18n = useI18n();
  const { t, locale, timeZone } = i18n;
  const user = useUser();
  const spaces = useSpaces();
  const createSpace = useCreateSpace();
  const openImport = useOpenImport();
  useDocumentTitle(t('home.docTitle'));
  const canImport = (spaces.data ?? []).some(canContribute);

  const today = new Intl.DateTimeFormat(locale, { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric', timeZone }).format(new Date());
  const firstName = user.displayName.trim().split(/\s+/)[0] ?? user.displayName;
  const next: [string, IconName, string, string][] = [
    ...(user.isAdmin
      ? ([
          ['/admin/members?open=invite', 'users', t('next.invite'), t('next.inviteHelp')],
          ['/admin/security', 'shield', t('next.security'), t('next.securityHelp')],
          ['/admin/general', 'globe', t('next.general'), t('next.generalHelp')],
        ] as [string, IconName, string, string][])
      : []),
    ['/account', 'user', t('next.profile'), t('next.profileHelp')],
  ];

  return (
    <View
      crumbs={[{ label: t('nav.home') }]}
      actions={canImport ? (
        <button type="button" className="btn btn-primary" onClick={openImport} title={`${t('home.shortcut.import')} (I)`}>
          <Icon name="upload" />
          <span className="btn-label">{t('import.open')}</span>
        </button>
      ) : undefined}
    >
      <div className="pad">
        <div className="home">
          <div className="stack">
            <div>
              <h1 className="page-title">{t('home.hello', { name: firstName })}</h1>
              <p className="page-intro">{today.charAt(0).toUpperCase() + today.slice(1)}</p>
            </div>
            <section aria-labelledby="home-spaces">
              <div className="row-between">
                <h2 className="section-title" id="home-spaces">{t('home.spaces')}</h2>
                <Link className="link" to="/spaces">{t('nav.allSpaces')}</Link>
              </div>
              <div className="tiles">
                {(spaces.data ?? []).map((space) => (
                  <Link key={space.id} className="tile" to={`/spaces/${space.slug}`}>
                    <SpaceIcon space={space} large />
                    <span>
                      <span className="tile-name">{space.name}</span>
                      <span className="tile-sub">{i18n.tp('home.spaceDocuments', space.documentCount)}</span>
                      <span className="tile-sub">{i18n.tp('home.spaceMembers', space.memberCount)}</span>
                      <span className="tile-sub">{t('home.spaceRole', { role: t(`spaceRole.${space.role as SpaceRole}`) })}</span>
                    </span>
                  </Link>
                ))}
                <button type="button" className="tile tile-new" onClick={createSpace}>
                  <span className="space-ico space-ico-lg"><Icon name="plus" /></span>
                  <span>{t('nav.createSpace')}</span>
                </button>
              </div>
            </section>
            <section aria-labelledby="home-start">
              <h2 className="section-title" id="home-start">{t('home.start')}</h2>
              <ul className="list">
                {next.map(([to, icon, title, help]) => (
                  <li key={to}>
                    <Link className="list-row" to={to}>
                      <span className="cat-ico"><Icon name={icon} /></span>
                      <span className="list-main">
                        <span className="list-title">{title}</span>
                        <span className="list-sub">{help}</span>
                      </span>
                      <Icon name="chevron-right" />
                    </Link>
                  </li>
                ))}
              </ul>
            </section>
          </div>
          <aside className="stack">
            <Attention />
            <section aria-labelledby="home-shortcuts">
              <h2 className="section-title" id="home-shortcuts">{t('home.shortcuts')}</h2>
              <ul className="shortcuts">
                {canImport && <li><span>{t('home.shortcut.import')}</span><kbd className="kbd">I</kbd></li>}
                <li><span>{t('home.shortcut.sidebar')}</span><kbd className="kbd">[</kbd></li>
                <li><span>{t('home.shortcut.close')}</span><kbd className="kbd">{t('common.escape')}</kbd></li>
              </ul>
            </section>
          </aside>
        </div>
      </div>
    </View>
  );
}

/**
 * To do: the documents the person imported whose import failed, and those still being indexed.
 * Asked again every ten seconds while one is in progress.
 */
function Attention() {
  const i18n = useI18n();
  const { t } = i18n;
  const attention = useQuery({
    queryKey: keys.attention,
    queryFn: () => call(() => api.GET('/api/v1/imports/attention')),
    refetchInterval: whileExtracting((data) => data.some((item) => item.extractionStatus === 'PENDING')),
  });
  if (!attention.data) return null;
  const failed = attention.data.filter((item) => item.extractionStatus === 'FAILED');
  const pending = attention.data.filter((item) => item.extractionStatus === 'PENDING');

  const group = (id: string, label: string, items: AttentionItem[], failure: boolean) => items.length > 0 && (
    <div>
      <h3 className={failure ? 'inbox-head is-err' : 'inbox-head'} id={id}>
        {failure ? <Icon name="alert" /> : <Spinner />}
        <span>{label}</span>
        <span className="n">{items.length}</span>
      </h3>
      <ul aria-labelledby={id}>
        {items.map((item) => (
          <li key={item.id}>
            <Link to={`/documents/${item.id}`}>
              <span>{item.title}</span>
              <span className="muted">
                {item.space.name}
                {failure && <>, {failureCause(i18n, item.extractionError)}</>}
              </span>
            </Link>
          </li>
        ))}
      </ul>
    </div>
  );

  return (
    <section aria-labelledby="home-attention">
      <h2 className="section-title" id="home-attention">{t('home.attention')}</h2>
      {failed.length + pending.length === 0 ? (
        <p className="help">{t('home.attention.none')}</p>
      ) : (
        <div className="inbox">
          {group('home-attention-failed', t('home.attention.failed'), failed, true)}
          {group('home-attention-pending', t('home.attention.pending'), pending, false)}
        </div>
      )}
    </section>
  );
}
