import type { ReactNode } from 'react';
import { NavLink } from 'react-router';
import { useI18n } from '../i18n/i18n';
import { useUser } from '../session/session';
import { useDocumentTitle, useVerticalFade } from '../ui/bits';
import { Icon, type IconName } from '../ui/Icon';
import { usePanel } from '../ui/preferences';
import { View } from './AppShell';

type Section = 'account' | 'general' | 'members' | 'security';

/** My account and Administration: the settings menu on the left, the page on the right. */
export function SettingsView({ section, title, intro, wide, actions, children }: {
  section: Section;
  title: string;
  intro?: ReactNode;
  wide?: boolean;
  actions?: ReactNode;
  children: ReactNode;
}) {
  const { t } = useI18n();
  const user = useUser();
  const [menuShown, toggleMenu] = usePanel('settings');
  const fade = useVerticalFade<HTMLElement>();
  useDocumentTitle(title);

  const link = (to: string, icon: IconName, label: string) => (
    <NavLink className="nav-link" to={to}>
      <Icon name={icon} />
      <span className="label">{label}</span>
    </NavLink>
  );
  const crumbs = section === 'account'
    ? [{ label: t('nav.account'), to: '/account' }, { label: title }]
    : [{ label: t('admin.title'), to: '/admin/general' }, { label: title }];
  const toggleLabel = menuShown ? t('settings.hideMenu') : t('settings.showMenu');

  return (
    <View
      crumbs={crumbs}
      actions={(
        <>
          <button type="button" className="icon-btn settings-toggle" onClick={toggleMenu} aria-label={toggleLabel} title={toggleLabel} aria-expanded={menuShown} aria-controls="settings-nav">
            <Icon name="sidebar-left" />
          </button>
          {actions}
        </>
      )}
    >
      <div className={menuShown ? 'settings' : 'settings hide-settings'}>
        <nav className="settings-nav fade-y" id="settings-nav" aria-label={t('settings.nav')} ref={fade}>
          <h2>{t('settings.account')}</h2>
          {link('/account', 'user', t('account.title'))}
          {user.isAdmin && (
            <>
              <h2>{t('settings.organization')}</h2>
              {link('/admin/general', 'settings', t('admin.general'))}
              {link('/admin/members', 'users', t('admin.members'))}
              {link('/admin/security', 'shield', t('admin.security'))}
            </>
          )}
        </nav>
        <div className="settings-main">
          <div className={wide ? 'settings-inner is-wide' : 'settings-inner'}>
            <h1 className="page-title">{title}</h1>
            {intro && <p className="page-intro">{intro}</p>}
            {children}
          </div>
        </div>
      </div>
    </View>
  );
}
