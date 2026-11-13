import { useMutation } from '@tanstack/react-query';
import { useEffect, useRef, useState, type FormEvent } from 'react';
import { Link, useLocation } from 'react-router';
import { api, ApiError, call } from '../api/client';
import { errorMessage } from '../i18n/errors';
import { useI18n, type I18n } from '../i18n/i18n';
import { AuthLayout } from '../layouts/AuthLayout';
import { forgetSignOutReason, startSession, useSession, type SignOutReason } from '../session/session';
import { useDocumentTitle } from '../ui/bits';
import { Callout, checks, hasProblems, message, TextField, useFocusFirstProblem, validate, type Problems } from '../ui/forms';
import { Spinner } from '../ui/Icon';

/** The message of a refused sign-in: wrong password, account locked or disabled. */
export function loginRefusal(i18n: I18n, error: unknown): string {
  if (error instanceof ApiError) {
    if (error.code === 'invalid_credentials') {
      const remaining = error.number('remainingAttempts');
      const minutes = error.number('lockoutMinutes');
      const warning = remaining !== undefined && minutes !== undefined && remaining > 0
        ? ` ${i18n.tp('login.remaining', remaining, { duration: i18n.duration(minutes) })}`
        : '';
      return i18n.t('login.invalid') + warning;
    }
    if (error.code === 'login_locked') {
      return i18n.t('login.locked', { duration: i18n.duration(error.number('retryAfterMinutes') ?? 15) });
    }
    if (error.code === 'account_disabled') return i18n.t('login.disabled');
  }
  return errorMessage(i18n, error);
}

export function LoginPage() {
  const i18n = useI18n();
  const { t } = i18n;
  const { organization } = useSession();
  const location = useLocation();
  const notice = (location.state as { notice?: SignOutReason | null } | null)?.notice ?? null;
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [problems, setProblems] = useState<Problems>({});
  const form = useRef<HTMLFormElement>(null);
  const passwordField = useRef<HTMLInputElement>(null);
  useDocumentTitle(t('login.docTitle'));
  useFocusFirstProblem(form, problems);
  useEffect(() => forgetSignOutReason(), []);

  // On success the session gets its person, and the page leaves for the requested address.
  const login = useMutation({
    mutationFn: () => call(() => api.POST('/api/v1/auth/login', { body: { email: email.trim(), password } })),
    onSuccess: startSession,
    onError: () => {
      setPassword('');
      passwordField.current?.focus();
    },
  });

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const found = validate({ email: checks.email(email), password: password ? null : { code: 'required' } });
    setProblems(found);
    if (!hasProblems(found)) login.mutate();
  };

  return (
    <AuthLayout>
      <h1>{t('login.title')}</h1>
      {organization && <p className="lead">{organization.name}</p>}
      <div className="auth-panel">
        <form className="form" ref={form} noValidate onSubmit={submit}>
          {login.isError ? (
            <Callout tone="warn" role="alert">{loginRefusal(i18n, login.error)}</Callout>
          ) : notice === 'expired' ? (
            <Callout role="status">{t('login.expired')}</Callout>
          ) : notice === 'signedOut' ? (
            <Callout tone="ok" role="status">{t('login.signedOut')}</Callout>
          ) : null}
          <TextField
            label={t('login.email')}
            type="email"
            name="email"
            autoComplete="username"
            value={email}
            onChange={(event) => setEmail(event.target.value)}
            error={message(i18n, problems.email)}
          />
          <TextField
            ref={passwordField}
            label={t('login.password')}
            type="password"
            name="password"
            autoComplete="current-password"
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            error={message(i18n, problems.password)}
            aside={<Link className="link" to="/forgot-password">{t('login.forgot')}</Link>}
          />
          <button type="submit" className="btn btn-primary btn-lg btn-block" disabled={login.isPending}>
            {login.isPending && <Spinner />}
            {t('login.submit')}
          </button>
        </form>
      </div>
      <p className="auth-alt">{t('login.noAccount')}</p>
    </AuthLayout>
  );
}
