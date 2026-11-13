import { useMutation, useQuery } from '@tanstack/react-query';
import { useRef, useState, type FormEvent } from 'react';
import { Link, useLocation, useNavigate } from 'react-router';
import { api, ApiError, call } from '../api/client';
import { errorMessage } from '../i18n/errors';
import { useI18n } from '../i18n/i18n';
import { AuthLayout } from '../layouts/AuthLayout';
import { startSession } from '../session/session';
import { useDocumentTitle } from '../ui/bits';
import { Callout, checks, hasProblems, message, problemsOf, TextField, useFocusFirstProblem, validate, type Problems } from '../ui/forms';
import { Spinner } from '../ui/Icon';
import { useNotify } from '../ui/Toast';

/**
 * The page opened from a reset link (/reset-password#token). The token stays in the fragment of the
 * address: the browser never sends it to the server in the address, only in the body of a request.
 */
export function ResetPasswordPage() {
  const i18n = useI18n();
  const { t } = i18n;
  const token = useLocation().hash.slice(1);
  useDocumentTitle(t('reset.docTitle'));

  const details = useQuery({
    queryKey: ['password-reset', token],
    queryFn: () => call(() => api.POST('/api/v1/password-reset/inspect', { body: { token } })),
    enabled: token.length > 0,
    retry: false,
    staleTime: Infinity,
  });

  const invalid = !token || (details.error instanceof ApiError && details.error.status === 404);
  if (invalid) {
    return (
      <AuthLayout>
        <h1>{t('reset.invalidTitle')}</h1>
        <div className="auth-panel">
          <Callout tone="warn">{t('reset.invalid')}</Callout>
        </div>
        <p className="auth-alt"><Link className="link" to="/login">{t('common.backToLogin')}</Link></p>
      </AuthLayout>
    );
  }
  if (details.isError) {
    return (
      <AuthLayout>
        <h1>{t('reset.title')}</h1>
        <div className="auth-panel form">
          <Callout tone="error" role="alert">{errorMessage(i18n, details.error)}</Callout>
          <button type="button" className="btn btn-block" onClick={() => void details.refetch()}>{t('common.retry')}</button>
        </div>
      </AuthLayout>
    );
  }
  if (!details.data) {
    return (
      <AuthLayout>
        <h1>{t('reset.title')}</h1>
        <div className="auth-panel" role="status"><Spinner /><span className="sr-only">{t('common.loading')}</span></div>
      </AuthLayout>
    );
  }
  return <ResetForm token={token} details={details.data} />;
}

function ResetForm({ token, details }: {
  token: string;
  details: { displayName: string; email: string; createdBy?: string | null; expiresAt: string; passwordMinLength: number };
}) {
  const i18n = useI18n();
  const { t } = i18n;
  const navigate = useNavigate();
  const notify = useNotify();
  const [password, setPassword] = useState('');
  const [confirmation, setConfirmation] = useState('');
  const [problems, setProblems] = useState<Problems>({});
  const form = useRef<HTMLFormElement>(null);
  useFocusFirstProblem(form, problems);

  const complete = useMutation({
    mutationFn: () => call(() => api.POST('/api/v1/password-reset/complete', { body: { token, password } })),
    onSuccess: (info) => {
      startSession(info);
      // Replaces the address that holds the token, so that it does not stay in the history.
      navigate('/', { replace: true });
      notify(t('reset.done'));
    },
    onError: (error) => setProblems(problemsOf(error)),
  });

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const found = validate({
      password: checks.password(password, details.passwordMinLength),
      confirmation: checks.confirmation(confirmation, password),
    });
    setProblems(found);
    if (!hasProblems(found)) complete.mutate();
  };

  const when = i18n.relative(details.expiresAt);
  const formError = complete.isError && !hasProblems(problemsOf(complete.error));
  return (
    <AuthLayout>
      <h1>{t('reset.title')}</h1>
      <p className="lead">{t('reset.account', { name: details.displayName, email: details.email })}</p>
      <div className="auth-panel">
        <form className="form" ref={form} noValidate onSubmit={submit}>
          {formError && (
            <Callout tone="error" role="alert">
              {complete.error instanceof ApiError && complete.error.code === 'invalid_link' ? t('reset.invalid') : errorMessage(i18n, complete.error)}
            </Callout>
          )}
          {/* Lets the password manager file the new password under the right account. */}
          <input type="email" name="username" autoComplete="username" value={details.email} readOnly hidden />
          <TextField
            label={t('reset.new')}
            type="password"
            autoComplete="new-password"
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            help={t('common.passwordHelp', { min: details.passwordMinLength })}
            error={message(i18n, problems.password)}
          />
          <TextField
            label={t('reset.confirm')}
            type="password"
            autoComplete="new-password"
            value={confirmation}
            onChange={(event) => setConfirmation(event.target.value)}
            error={message(i18n, problems.confirmation)}
          />
          <button type="submit" className="btn btn-primary btn-lg btn-block" disabled={complete.isPending}>
            {complete.isPending && <Spinner />}
            {t('reset.submit')}
          </button>
        </form>
      </div>
      <p className="auth-alt">
        {details.createdBy ? t('reset.byAdmin', { name: details.createdBy, when }) : t('reset.byServer', { when })}
      </p>
    </AuthLayout>
  );
}
