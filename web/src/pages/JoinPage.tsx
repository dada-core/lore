import { useMutation, useQuery } from '@tanstack/react-query';
import { useRef, useState, type FormEvent } from 'react';
import { Link, useLocation, useNavigate } from 'react-router';
import { api, ApiError, call, type Schemas } from '../api/client';
import { errorMessage } from '../i18n/errors';
import { useI18n } from '../i18n/i18n';
import { AuthLayout } from '../layouts/AuthLayout';
import { signOut, startSession, useSession } from '../session/session';
import { useDocumentTitle } from '../ui/bits';
import { Callout, checks, hasProblems, message, problemsOf, TextField, useFocusFirstProblem, validate, type Problems } from '../ui/forms';
import { Spinner } from '../ui/Icon';
import { useNotify } from '../ui/Toast';

/** The page opened from an invitation link (/join#token): create one's account and sign in. */
export function JoinPage() {
  const i18n = useI18n();
  const { t } = i18n;
  const token = useLocation().hash.slice(1);
  useDocumentTitle(t('join.docTitle'));

  const details = useQuery({
    queryKey: ['invitation', token],
    queryFn: () => call(() => api.POST('/api/v1/invitations/inspect', { body: { token } })),
    enabled: token.length > 0,
    retry: false,
    staleTime: Infinity,
  });

  if (!token || (details.error instanceof ApiError && details.error.status === 404)) {
    return (
      <AuthLayout>
        <h1>{t('join.invalidTitle')}</h1>
        <div className="auth-panel">
          <Callout tone="warn">{t('join.invalid')}</Callout>
        </div>
        <p className="auth-alt"><Link className="link" to="/login">{t('common.signIn')}</Link></p>
      </AuthLayout>
    );
  }
  if (details.isError) {
    return (
      <AuthLayout>
        <h1>{t('join.docTitle')}</h1>
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
        <h1>{t('join.docTitle')}</h1>
        <div className="auth-panel" role="status"><Spinner /><span className="sr-only">{t('common.loading')}</span></div>
      </AuthLayout>
    );
  }
  return <JoinForm token={token} invitation={details.data} />;
}

function JoinForm({ token, invitation }: { token: string; invitation: Schemas['InvitationDetails'] }) {
  const i18n = useI18n();
  const { t } = i18n;
  const { user } = useSession();
  const navigate = useNavigate();
  const notify = useNotify();
  const [displayName, setDisplayName] = useState('');
  const [email, setEmail] = useState(invitation.email ?? '');
  const [password, setPassword] = useState('');
  const [confirmation, setConfirmation] = useState('');
  const [problems, setProblems] = useState<Problems>({});
  const form = useRef<HTMLFormElement>(null);
  useFocusFirstProblem(form, problems);

  const accept = useMutation({
    mutationFn: () => call(() => api.POST('/api/v1/invitations/accept', {
      body: { token, displayName: displayName.trim(), email: email.trim(), password },
    })),
    onSuccess: (info) => {
      startSession(info);
      navigate('/', { replace: true });
      notify(t('join.welcome', { org: invitation.organizationName }));
    },
    onError: (error) => setProblems(problemsOf(error)),
  });

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const found = validate({
      displayName: checks.required(displayName),
      email: checks.email(email),
      password: checks.password(password, invitation.passwordMinLength),
      confirmation: checks.confirmation(confirmation, password),
    });
    setProblems(found);
    if (!hasProblems(found)) accept.mutate();
  };

  const role = t(`role.${invitation.role as 'OWNER' | 'ADMIN' | 'MEMBER'}`);
  const formError = accept.isError && !hasProblems(problemsOf(accept.error));
  return (
    <AuthLayout wide>
      <h1>{t('join.title', { org: invitation.organizationName })}</h1>
      <p className="lead">
        {invitation.invitedBy ? t('join.lead', { name: invitation.invitedBy, role }) : t('join.leadAnonymous', { role })}
      </p>
      <div className="auth-panel">
        {user ? (
          <div className="form">
            <Callout>{t('join.signedInAs', { name: user.displayName })}</Callout>
            <button type="button" className="btn btn-lg btn-block" onClick={() => void signOut()}>{t('common.signOut')}</button>
          </div>
        ) : (
          <form className="form" ref={form} noValidate onSubmit={submit}>
            {formError && (
              <Callout tone="error" role="alert">
                {accept.error instanceof ApiError && accept.error.code === 'invalid_link' ? t('join.invalid') : errorMessage(i18n, accept.error)}
              </Callout>
            )}
            <TextField
              label={t('join.fullName')}
              autoComplete="name"
              placeholder={t('join.fullNamePlaceholder')}
              value={displayName}
              onChange={(event) => setDisplayName(event.target.value)}
              error={message(i18n, problems.displayName)}
            />
            <TextField
              label={t('join.email')}
              type="email"
              autoComplete="username"
              value={email}
              readOnly={!!invitation.email}
              onChange={(event) => setEmail(event.target.value)}
              help={invitation.email ? t('join.emailReserved') : undefined}
              error={message(i18n, problems.email)}
            />
            <div className="form-2">
              <TextField
                label={t('join.password')}
                type="password"
                autoComplete="new-password"
                value={password}
                onChange={(event) => setPassword(event.target.value)}
                help={t('common.passwordHelp', { min: invitation.passwordMinLength })}
                error={message(i18n, problems.password)}
              />
              <TextField
                label={t('join.confirm')}
                type="password"
                autoComplete="new-password"
                value={confirmation}
                onChange={(event) => setConfirmation(event.target.value)}
                error={message(i18n, problems.confirmation)}
              />
            </div>
            <button type="submit" className="btn btn-primary btn-lg btn-block" disabled={accept.isPending}>
              {accept.isPending && <Spinner />}
              {t('join.submit')}
            </button>
          </form>
        )}
      </div>
      <p className="auth-alt">{t('join.expires', { date: i18n.date(invitation.expiresAt) })}</p>
    </AuthLayout>
  );
}
