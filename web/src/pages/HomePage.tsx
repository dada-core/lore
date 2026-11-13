import { Link } from 'react-router';
import { useI18n } from '../i18n/i18n';
import { View } from '../layouts/AppShell';
import { useUser } from '../session/session';
import { useDocumentTitle } from '../ui/bits';
import { Icon, type IconName } from '../ui/Icon';

/**
 * Home. It fills up as Lore grows: spaces, recent documents and things to handle arrive with
 * the spaces and the import. For now: where to start, and the keyboard shortcuts.
 */
export function HomePage() {
  const { t, locale, timeZone } = useI18n();
  const user = useUser();
  useDocumentTitle(t('home.docTitle'));

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
    <View crumbs={[{ label: t('nav.home') }]}>
      <div className="pad">
        <div className="home">
          <div className="stack">
            <div>
              <h1 className="page-title">{t('home.hello', { name: firstName })}</h1>
              <p className="page-intro">{today.charAt(0).toUpperCase() + today.slice(1)}</p>
            </div>
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
          <aside className="stack" aria-labelledby="home-shortcuts">
            <section>
              <h2 className="section-title" id="home-shortcuts">{t('home.shortcuts')}</h2>
              <ul className="shortcuts">
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
