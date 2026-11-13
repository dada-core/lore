import { createContext, useContext, useEffect, useState } from 'react';
import { Navigate, Outlet, useLocation, useRouteError } from 'react-router';
import { browserLocale, browserTimeZone, I18nProvider, toLocale, useI18n, type Locale } from './i18n/i18n';
import { SessionProvider, useSessionQuery } from './session/session';
import { useDocumentTitle } from './ui/bits';
import { Icon, Logo, Spinner } from './ui/Icon';

/** Lets the setup screen show the interface in the language being chosen, before anything is saved. */
const LocalePreview = createContext<(locale: Locale | null) => void>(() => {});

export function useLocalePreview(): (locale: Locale | null) => void {
  return useContext(LocalePreview);
}

/**
 * Loads the session (installation state, organization, signed-in person), then chooses the
 * language: the person's, otherwise the organization's, otherwise the browser's.
 */
export function Root() {
  const session = useSessionQuery();
  const { pathname } = useLocation();
  const [preview, setPreview] = useState<Locale | null>(null);

  const info = session.data;
  const locale = preview ?? toLocale(info?.user?.locale) ?? toLocale(info?.organization?.defaultLocale) ?? browserLocale();
  const timeZone = info?.organization?.timeZone ?? browserTimeZone();

  useEffect(() => {
    document.documentElement.lang = locale;
  }, [locale]);

  if (!info) {
    return (
      <I18nProvider locale={locale} timeZone={timeZone}>
        {session.isError ? <Unreachable onRetry={() => void session.refetch()} retrying={session.isFetching} /> : <Splash />}
      </I18nProvider>
    );
  }

  return (
    <I18nProvider locale={locale} timeZone={timeZone}>
      <LocalePreview value={setPreview}>
        <SessionProvider value={info}>
          {info.setupRequired && pathname !== '/setup' ? <Navigate to="/setup" replace /> : <Outlet />}
        </SessionProvider>
      </LocalePreview>
    </I18nProvider>
  );
}

function Splash() {
  const { t } = useI18n();
  return (
    <div className="splash" role="status">
      <Spinner />
      <span className="sr-only">{t('common.loading')}</span>
    </div>
  );
}

function Unreachable({ onRetry, retrying }: { onRetry: () => void; retrying: boolean }) {
  const { t } = useI18n();
  useDocumentTitle(t('offline.title'));
  return (
    <main className="auth">
      <div className="auth-card">
        <div className="auth-logo"><Logo size={36} /></div>
        <h1>{t('offline.title')}</h1>
        <p className="lead">{t('offline.body')}</p>
        <button type="button" className="btn btn-primary btn-lg btn-block" onClick={onRetry} disabled={retrying}>
          {retrying ? <Spinner /> : <Icon name="refresh" />}
          {t('common.retry')}
        </button>
      </div>
    </main>
  );
}

/** Shown instead of a screen that failed to display: an error of the interface itself. */
export function CrashPage() {
  const error = useRouteError();
  useEffect(() => {
    console.error(error);
  }, [error]);
  const locale = browserLocale();
  return (
    <I18nProvider locale={toLocale(document.documentElement.lang) ?? locale} timeZone={browserTimeZone()}>
      <Crash />
    </I18nProvider>
  );
}

function Crash() {
  const { t } = useI18n();
  useDocumentTitle(t('crash.title'));
  return (
    <main className="auth">
      <div className="auth-card">
        <div className="auth-logo"><Logo size={36} /></div>
        <h1>{t('crash.title')}</h1>
        <p className="lead">{t('crash.body')}</p>
        <button type="button" className="btn btn-primary btn-lg btn-block" onClick={() => window.location.reload()}>
          <Icon name="refresh" />
          {t('crash.reload')}
        </button>
      </div>
    </main>
  );
}
