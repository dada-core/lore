import { createContext, Fragment, useCallback, useContext, useEffect, useState, type ReactNode } from 'react';
import { Link, NavLink, Outlet, useLocation } from 'react-router';
import { useI18n } from '../i18n/i18n';
import { signOut, useSession, useUser } from '../session/session';
import { Avatar, initials, useVerticalFade } from '../ui/bits';
import { Icon } from '../ui/Icon';
import { Menu, MenuItem, MenuLink, MenuSeparator } from '../ui/Menu';
import { nextTheme, setTheme, usePanel, useTheme } from '../ui/preferences';
import { useNotify } from '../ui/Toast';

const NARROW = '(max-width: 960px)';

interface Shell {
  /** Shows the sidebar: a drawer on small screens, the unfolded column otherwise. */
  showNavigation: () => void;
}
const ShellContext = createContext<Shell>({ showNavigation: () => {} });

function isTyping(): boolean {
  const element = document.activeElement;
  return !!element && (/^(INPUT|TEXTAREA|SELECT)$/.test(element.tagName) || (element as HTMLElement).isContentEditable);
}

/** The structure of every screen once signed in: sidebar on the left, the view on the right. */
export function AppShell() {
  const { t } = useI18n();
  const { pathname } = useLocation();
  const [navShown, toggleNav] = usePanel('nav');
  // The drawer of small screens belongs to the view it was opened on: a new view closes it.
  const [drawerPath, setDrawerPath] = useState<string | null>(null);
  const drawerOpen = drawerPath === pathname;
  const setDrawerOpen = useCallback((open: boolean) => setDrawerPath(open ? pathname : null), [pathname]);

  const showNavigation = useCallback(() => {
    if (window.matchMedia(NARROW).matches) setDrawerOpen(true);
    else if (!navShown) toggleNav();
  }, [navShown, toggleNav, setDrawerOpen]);

  // A new view: back to the top, and the focus on its title for screen readers.
  useEffect(() => {
    window.scrollTo(0, 0);
    const title = document.querySelector<HTMLElement>('#content h1');
    if (title) {
      title.tabIndex = -1;
      title.focus({ preventScroll: true });
    }
  }, [pathname]);

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && drawerOpen) {
        setDrawerOpen(false);
        return;
      }
      if (event.key !== '[' || event.ctrlKey || event.metaKey || event.altKey || isTyping()) return;
      if (document.querySelector('dialog[open]')) return;
      event.preventDefault();
      if (window.matchMedia(NARROW).matches) setDrawerOpen(!drawerOpen);
      else toggleNav();
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [drawerOpen, setDrawerOpen, toggleNav]);

  return (
    <ShellContext value={{ showNavigation }}>
      <div className={navShown ? 'app' : 'app hide-nav'}>
        <Sidebar open={drawerOpen} onFold={toggleNav} />
        {drawerOpen && (
          <button type="button" className="scrim" aria-label={t('nav.closeSidebar')} onClick={() => setDrawerOpen(false)} />
        )}
        <div className="main">
          <Outlet />
        </div>
      </div>
    </ShellContext>
  );
}

function Sidebar({ open, onFold }: { open: boolean; onFold: () => void }) {
  const { t } = useI18n();
  const notify = useNotify();
  const { organization } = useSession();
  const user = useUser();
  const theme = useTheme();
  const fade = useVerticalFade<HTMLDivElement>();
  const name = organization?.name ?? 'Lore';

  const cycleTheme = () => {
    const theme2 = nextTheme(theme);
    setTheme(theme2);
    notify(t(`theme.now.${theme2}`));
  };

  return (
    <aside className={open ? 'sidebar is-open' : 'sidebar'} id="sidebar" aria-label={t('nav.main')}>
      <div className="ws">
        <Menu
          triggerClassName="ws-btn"
          trigger={(
            <>
              <span className="ws-logo" aria-hidden="true">{initials(name)}</span>
              <span className="name">{name}</span>
              <Icon name="chevron" />
            </>
          )}
        >
          {user.isAdmin && <MenuLink icon="settings" to="/admin/general">{t('nav.orgSettings')}</MenuLink>}
          {user.isAdmin && <MenuLink icon="users" to="/admin/members?open=invite">{t('nav.invite')}</MenuLink>}
          {user.isAdmin && <MenuSeparator />}
          <MenuItem icon="logout" onSelect={() => void signOut()}>{t('common.signOut')}</MenuItem>
        </Menu>
        <button
          type="button"
          className="icon-btn side-collapse"
          onClick={onFold}
          aria-label={t('nav.hideSidebar')}
          title={`${t('nav.hideSidebar')} ([)`}
          aria-controls="sidebar"
          aria-expanded="true"
        >
          <Icon name="sidebar-left" />
        </button>
      </div>

      <div className="side-scroll fade-y" ref={fade}>
        <nav aria-label={t('nav.environment')}>
          <ul className="nav">
            <li>
              <NavLink className="nav-link" to="/" end>
                <Icon name="home" />
                <span className="label">{t('nav.home')}</span>
              </NavLink>
            </li>
          </ul>
        </nav>
        {user.isAdmin && (
          <nav aria-labelledby="nav-organization">
            <div className="nav-head"><h2 id="nav-organization">{t('nav.organization')}</h2></div>
            <ul className="nav">
              <li>
                <NavLink className="nav-link" to="/admin">
                  <Icon name="settings" />
                  <span className="label">{t('nav.admin')}</span>
                </NavLink>
              </li>
            </ul>
          </nav>
        )}
      </div>

      <div className="side-foot">
        <NavLink className="me" to="/account" title={t('nav.account')}>
          <Avatar name={user.displayName} seed={user.id} />
          <span>{user.displayName}</span>
        </NavLink>
        <button type="button" className="icon-btn" onClick={cycleTheme} aria-label={t('theme.change')} title={t('theme.change')}>
          <Icon name="theme" />
        </button>
        <button type="button" className="icon-btn" onClick={() => void signOut()} aria-label={t('common.signOut')} title={t('common.signOut')}>
          <Icon name="logout" />
        </button>
      </div>
    </aside>
  );
}

export interface Crumb {
  label: string;
  to?: string;
}

/** A view: its header (breadcrumb on the left, actions on the right) and its content. */
export function View({ crumbs, actions, children }: { crumbs: Crumb[]; actions?: ReactNode; children: ReactNode }) {
  const { t } = useI18n();
  const { showNavigation } = useContext(ShellContext);
  return (
    <>
      <header className="view-header">
        <button
          type="button"
          className="icon-btn menu-btn"
          onClick={showNavigation}
          aria-label={t('nav.showSidebar')}
          title={`${t('nav.showSidebar')} ([)`}
          aria-controls="sidebar"
        >
          <Icon name="sidebar-left" />
        </button>
        <nav className="crumbs" aria-label={t('nav.breadcrumb')}>
          {crumbs.map((crumb, index) =>
            index < crumbs.length - 1 && crumb.to ? (
              <Fragment key={crumb.label}>
                <Link to={crumb.to}>{crumb.label}</Link>
                <span className="sep" aria-hidden="true">›</span>
              </Fragment>
            ) : (
              <span key={crumb.label} className="current" aria-current="page">{crumb.label}</span>
            ),
          )}
        </nav>
        {actions && <div className="vh-actions">{actions}</div>}
      </header>
      <main className="view-body" id="content">{children}</main>
    </>
  );
}
