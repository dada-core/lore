import { useMutation, useQuery } from '@tanstack/react-query';
import { useRef, useState, type FormEvent } from 'react';
import { api, call, type Schemas } from '../api/client';
import { keys, queryClient } from '../api/queryClient';
import { errorMessage } from '../i18n/errors';
import { LOCALE_NAMES, toLocale, useI18n, type Locale } from '../i18n/i18n';
import { messages } from '../i18n/messages';
import { SettingsView } from '../layouts/SettingsView';
import { refreshSession, useSession, useUser } from '../session/session';
import { Avatar } from '../ui/bits';
import {
  Callout,
  checks,
  hasProblems,
  message,
  problemsOf,
  SettingRow,
  useFocusFirstProblem,
  validate,
  type Problems,
} from '../ui/forms';
import { Spinner } from '../ui/Icon';
import { setTheme, THEMES, useTheme, type Theme } from '../ui/preferences';
import { useNotify } from '../ui/Toast';

type Account = Schemas['AccountView'];

/** My account: profile, preferences, password and open sessions. */
export function AccountPage() {
  const i18n = useI18n();
  const account = useQuery({ queryKey: keys.account, queryFn: () => call(() => api.GET('/api/v1/account')) });
  return (
    <SettingsView section="account" title={i18n.t('account.title')}>
      {account.data ? (
        <>
          <ProfileForm account={account.data} />
          <Preferences account={account.data} />
          <PasswordForm account={account.data} />
          <Sessions />
        </>
      ) : account.isError ? (
        <div className="set-sec"><Callout tone="error" role="alert">{errorMessage(i18n, account.error)}</Callout></div>
      ) : (
        <div className="set-sec" role="status"><Spinner /><span className="sr-only">{i18n.t('common.loading')}</span></div>
      )}
    </SettingsView>
  );
}

function ProfileForm({ account }: { account: Account }) {
  const i18n = useI18n();
  const { t } = i18n;
  const notify = useNotify();
  const user = useUser();
  const [displayName, setDisplayName] = useState(account.displayName);
  const [email, setEmail] = useState(account.email);
  const [currentPassword, setCurrentPassword] = useState('');
  const [problems, setProblems] = useState<Problems>({});
  const form = useRef<HTMLFormElement>(null);
  useFocusFirstProblem(form, problems);
  const emailChanged = email.trim().toLowerCase() !== account.email;

  const save = useMutation({
    mutationFn: async () => {
      let view = account;
      if (displayName.trim() !== account.displayName) {
        view = await call(() => api.PATCH('/api/v1/account', { body: { displayName: displayName.trim() } }));
      }
      if (emailChanged) {
        view = await call(() => api.PUT('/api/v1/account/email', { body: { email: email.trim(), currentPassword } }));
      }
      return view;
    },
    onSuccess: (view) => {
      queryClient.setQueryData(keys.account, view);
      setEmail(view.email);
      setCurrentPassword('');
      notify(t('common.saved'));
    },
    onError: (error) => setProblems(problemsOf(error)),
    onSettled: () => {
      void refreshSession();
      void queryClient.invalidateQueries({ queryKey: keys.account });
    },
  });

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const found = validate({
      displayName: checks.required(displayName),
      email: checks.email(email),
      currentPassword: emailChanged && !currentPassword ? { code: 'required' } : null,
    });
    setProblems(found);
    if (!hasProblems(found)) save.mutate();
  };

  return (
    <form className="set-sec" ref={form} noValidate onSubmit={submit} aria-labelledby="account-profile">
      <h2 id="account-profile">{t('account.profile')}</h2>
      {save.isError && !hasProblems(problemsOf(save.error)) && (
        <div style={{ marginTop: 12 }}><Callout tone="error" role="alert">{errorMessage(i18n, save.error)}</Callout></div>
      )}
      <div className="set-box">
        <SettingRow label={t('account.fullName')} error={message(i18n, problems.displayName)}>
          {(ids) => (
            <div className="ctl-line">
              <Avatar name={displayName || account.displayName} seed={user.id} large />
              <input className="input" autoComplete="name" value={displayName} onChange={(event) => setDisplayName(event.target.value)} {...ids} />
            </div>
          )}
        </SettingRow>
        <SettingRow label={t('account.email')} help={t('account.emailHelp')} error={message(i18n, problems.email)}>
          {(ids) => <input className="input" type="email" autoComplete="email" value={email} onChange={(event) => setEmail(event.target.value)} {...ids} />}
        </SettingRow>
        {emailChanged && (
          <SettingRow label={t('account.passwordForEmail')} help={t('account.passwordForEmailHelp')} error={message(i18n, problems.currentPassword)}>
            {(ids) => (
              <input className="input" type="password" autoComplete="current-password" value={currentPassword} onChange={(event) => setCurrentPassword(event.target.value)} {...ids} />
            )}
          </SettingRow>
        )}
      </div>
      <div className="set-actions">
        <button type="submit" className="btn btn-primary" disabled={save.isPending}>
          {save.isPending && <Spinner />}
          {t('common.save')}
        </button>
      </div>
    </form>
  );
}

function Preferences({ account }: { account: Account }) {
  const i18n = useI18n();
  const { t } = i18n;
  const notify = useNotify();
  const { organization } = useSession();
  const theme = useTheme();
  const organizationLocale = toLocale(organization?.defaultLocale) ?? 'fr';

  const language = useMutation({
    mutationFn: (locale: string) => call(() => api.PATCH('/api/v1/account', { body: { locale } })),
    onSuccess: async (view) => {
      queryClient.setQueryData(keys.account, view);
      await refreshSession();
      // Announced in the language just chosen.
      notify(messages[toLocale(view.interfaceLocale) ?? organizationLocale]['account.languageSaved']);
    },
    onError: (error) => notify(errorMessage(i18n, error)),
  });

  return (
    <section className="set-sec" aria-labelledby="account-preferences">
      <h2 id="account-preferences">{t('account.preferences')}</h2>
      <div className="set-box">
        <SettingRow label={t('account.language')}>
          {(ids) => (
            <select
              className="select"
              value={account.locale ?? ''}
              disabled={language.isPending}
              onChange={(event) => language.mutate(event.target.value)}
              {...ids}
            >
              <option value="">{t('account.languageOrg', { language: LOCALE_NAMES[organizationLocale] })}</option>
              {(Object.keys(LOCALE_NAMES) as Locale[]).map((locale) => (
                <option key={locale} value={locale} lang={locale}>{LOCALE_NAMES[locale]}</option>
              ))}
            </select>
          )}
        </SettingRow>
        <SettingRow label={t('theme.label')} help={t('account.themeHelp')}>
          {(ids) => (
            <select className="select" value={theme} onChange={(event) => setTheme(event.target.value as Theme)} {...ids}>
              {THEMES.map((option) => <option key={option} value={option}>{t(`theme.${option}`)}</option>)}
            </select>
          )}
        </SettingRow>
      </div>
    </section>
  );
}

function PasswordForm({ account }: { account: Account }) {
  const i18n = useI18n();
  const { t } = i18n;
  const notify = useNotify();
  const [currentPassword, setCurrentPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [confirmation, setConfirmation] = useState('');
  const [signOutOthers, setSignOutOthers] = useState(true);
  const [problems, setProblems] = useState<Problems>({});
  const form = useRef<HTMLFormElement>(null);
  useFocusFirstProblem(form, problems);

  const change = useMutation({
    mutationFn: () => call(() => api.PUT('/api/v1/account/password', {
      body: { currentPassword, newPassword, signOutOtherSessions: signOutOthers },
    })),
    onSuccess: (result) => {
      setCurrentPassword('');
      setNewPassword('');
      setConfirmation('');
      const closed = result.closedSessions ?? 0;
      notify(closed > 0 ? `${t('account.passwordChanged')}. ${i18n.tp('account.closed', closed)}` : t('account.passwordChanged'));
      void queryClient.invalidateQueries({ queryKey: keys.sessions });
    },
    onError: (error) => setProblems(problemsOf(error)),
  });

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const found = validate({
      currentPassword: currentPassword ? null : { code: 'required' },
      newPassword: checks.password(newPassword, account.passwordMinLength),
      confirmation: checks.confirmation(confirmation, newPassword),
    });
    setProblems(found);
    if (!hasProblems(found)) change.mutate();
  };

  return (
    <form className="set-sec" ref={form} noValidate onSubmit={submit} aria-labelledby="account-password">
      <h2 id="account-password">{t('account.password')}</h2>
      {change.isError && !hasProblems(problemsOf(change.error)) && (
        <div style={{ marginTop: 12 }}><Callout tone="error" role="alert">{errorMessage(i18n, change.error)}</Callout></div>
      )}
      <input type="email" name="username" autoComplete="username" value={account.email} readOnly hidden />
      <div className="set-box">
        <SettingRow label={t('account.currentPassword')} error={message(i18n, problems.currentPassword)}>
          {(ids) => <input className="input" type="password" autoComplete="current-password" value={currentPassword} onChange={(event) => setCurrentPassword(event.target.value)} {...ids} />}
        </SettingRow>
        <SettingRow label={t('account.newPassword')} help={t('common.passwordHelp', { min: account.passwordMinLength })} error={message(i18n, problems.newPassword)}>
          {(ids) => <input className="input" type="password" autoComplete="new-password" value={newPassword} onChange={(event) => setNewPassword(event.target.value)} {...ids} />}
        </SettingRow>
        <SettingRow label={t('account.confirmPassword')} error={message(i18n, problems.confirmation)}>
          {(ids) => <input className="input" type="password" autoComplete="new-password" value={confirmation} onChange={(event) => setConfirmation(event.target.value)} {...ids} />}
        </SettingRow>
      </div>
      <div className="set-actions">
        <button type="submit" className="btn" disabled={change.isPending}>
          {change.isPending && <Spinner />}
          {t('account.changePassword')}
        </button>
        <label className="check">
          <input type="checkbox" checked={signOutOthers} onChange={(event) => setSignOutOthers(event.target.checked)} />
          {t('account.signOutOthers')}
        </label>
      </div>
    </form>
  );
}

function Sessions() {
  const i18n = useI18n();
  const { t } = i18n;
  const notify = useNotify();
  const sessions = useQuery({ queryKey: keys.sessions, queryFn: () => call(() => api.GET('/api/v1/account/sessions')) });
  const refresh = () => void queryClient.invalidateQueries({ queryKey: keys.sessions });

  const closeOne = useMutation({
    mutationFn: (id: string) => call(() => api.DELETE('/api/v1/account/sessions/{id}', { params: { path: { id } } })),
    onSuccess: () => notify(t('account.sessionClosed')),
    onError: (error) => notify(errorMessage(i18n, error)),
    onSettled: refresh,
  });
  const closeOthers = useMutation({
    mutationFn: () => call(() => api.POST('/api/v1/account/sessions/sign-out-others')),
    onSuccess: (result) => {
      const closed = result.closedSessions ?? 0;
      notify(closed > 0 ? i18n.tp('account.closed', closed) : t('account.noOtherSession'));
    },
    onError: (error) => notify(errorMessage(i18n, error)),
    onSettled: refresh,
  });

  const list = sessions.data ?? [];
  const others = list.filter((session) => !session.current).length;
  return (
    <section className="set-sec" aria-labelledby="account-sessions">
      <h2 id="account-sessions">{t('account.sessions')}</h2>
      {sessions.isError && <div style={{ marginTop: 12 }}><Callout tone="error" role="alert">{errorMessage(i18n, sessions.error)}</Callout></div>}
      <div className="set-box">
        {list.map((session) => {
          const name = t('account.sessionName', {
            browser: session.browser ?? t('account.unknownBrowser'),
            system: session.system ?? t('account.unknownSystem'),
          });
          const ip = session.ip ?? t('account.unknownIp');
          return (
            <div className="set-row" key={session.id}>
              <div className="lbl">
                <strong>{name}</strong>
                <p className="help">
                  {session.current
                    ? t('account.sessionCurrent', { ip })
                    : t('account.sessionOther', { ip, when: i18n.relative(session.lastActiveAt) })}
                </p>
              </div>
              <div className="ctl">
                {session.current ? (
                  <span className="pill p-green">{t('account.thisSession')}</span>
                ) : (
                  <button
                    type="button"
                    className="btn"
                    disabled={closeOne.isPending && closeOne.variables === session.id}
                    aria-label={t('account.signOutSessionOf', { name })}
                    onClick={() => closeOne.mutate(session.id)}
                  >
                    {t('account.signOutSession')}
                  </button>
                )}
              </div>
            </div>
          );
        })}
        {sessions.isPending && <div className="set-row" role="status"><Spinner /><span className="sr-only">{t('common.loading')}</span></div>}
      </div>
      <div className="set-actions">
        <button type="button" className="btn" disabled={others === 0 || closeOthers.isPending} onClick={() => closeOthers.mutate()}>
          {closeOthers.isPending && <Spinner />}
          {t('account.signOutAll')}
        </button>
        {others === 0 && !sessions.isPending && <span className="help">{t('account.noOtherSession')}</span>}
      </div>
    </section>
  );
}
