import { useMutation, useQuery } from '@tanstack/react-query';
import { useEffect, useRef, useState, type FormEvent } from 'react';
import { Link, Navigate } from 'react-router';
import { api, ApiError, call } from '../api/client';
import { keys } from '../api/queryClient';
import { errorMessage } from '../i18n/errors';
import { browserLocale, browserTimeZone, LOCALE_NAMES, LOCALES, toLocale, useI18n, type Locale } from '../i18n/i18n';
import { AuthLayout } from '../layouts/AuthLayout';
import { useLocalePreview } from '../Root';
import { startSession, useSession } from '../session/session';
import { useDocumentTitle } from '../ui/bits';
import {
  Callout,
  checks,
  hasProblems,
  message,
  problemsOf,
  SelectField,
  TextField,
  useFocusFirstProblem,
  validate,
  type Problems,
} from '../ui/forms';
import { Icon, Spinner, type IconName } from '../ui/Icon';

/** The default minimum length of a password, before the organization exists to set another one. */
const PASSWORD_MIN_LENGTH = 12;
const STEP_ONE_FIELDS = ['setupCode', 'organizationName', 'publicUrl', 'locale', 'timeZone'];

type Step = 1 | 2 | 3;

/**
 * First start: the organization (step 1) and its owner (step 2) are sent together, in one call,
 * so that an organization without an owner never exists. The setup code, printed in the server
 * logs, keeps a stranger who reaches a fresh installation from taking it over.
 */
export function SetupPage() {
  const i18n = useI18n();
  const { t } = i18n;
  const session = useSession();
  const setPreview = useLocalePreview();
  const [step, setStep] = useState<Step>(1);
  const [code, setCode] = useState('');
  const [organizationName, setOrganizationName] = useState('');
  const [publicUrl, setPublicUrl] = useState<string | null>(null);
  const [locale, setLocale] = useState<Locale>(() => toLocale(i18n.locale) ?? browserLocale());
  const [displayName, setDisplayName] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [confirmation, setConfirmation] = useState('');
  const [problems, setProblems] = useState<Problems>({});
  const [alreadyDone, setAlreadyDone] = useState(false);
  const form = useRef<HTMLFormElement>(null);
  useDocumentTitle(t('setup.docTitle'));
  useFocusFirstProblem(form, problems);
  useEffect(() => () => setPreview(null), [setPreview]);

  const status = useQuery({ queryKey: keys.setup, queryFn: () => call(() => api.GET('/api/v1/setup')), staleTime: Infinity });
  const fromEnvironment = status.data?.publicUrlFromEnvironment ?? false;
  const url = fromEnvironment ? status.data?.publicUrl ?? '' : publicUrl ?? window.location.origin;

  const onRefused = (error: unknown) => {
    if (error instanceof ApiError && error.code === 'setup_done') setAlreadyDone(true);
    const found = problemsOf(error);
    setProblems(found);
    if (Object.keys(found).some((field) => STEP_ONE_FIELDS.includes(field))) setStep(1);
  };

  const verify = useMutation({
    mutationFn: () => call(() => api.POST('/api/v1/setup/verify-code', { body: { setupCode: code.trim() } })),
    onSuccess: () => setStep(2),
    onError: onRefused,
  });

  const create = useMutation({
    mutationFn: () => call(() => api.POST('/api/v1/setup', {
      body: {
        setupCode: code.trim(),
        organizationName: organizationName.trim(),
        publicUrl: fromEnvironment ? null : url.trim(),
        locale,
        timeZone: browserTimeZone(),
        displayName: displayName.trim(),
        email: email.trim(),
        password,
      },
    })),
    onSuccess: (info) => {
      startSession(info);
      setStep(3);
    },
    onError: onRefused,
  });

  if (!session.setupRequired && step !== 3) return <Navigate to="/" replace />;

  const submitOrganization = (event: FormEvent) => {
    event.preventDefault();
    const found = validate({
      setupCode: code.trim() ? null : { code: 'required' },
      organizationName: checks.required(organizationName),
      publicUrl: fromEnvironment ? null : checks.url(url),
    });
    setProblems(found);
    if (!hasProblems(found)) verify.mutate();
  };

  const submitAccount = (event: FormEvent) => {
    event.preventDefault();
    const found = validate({
      displayName: checks.required(displayName),
      email: checks.email(email),
      password: checks.password(password, PASSWORD_MIN_LENGTH),
      confirmation: checks.confirmation(confirmation, password),
    });
    setProblems(found);
    if (!hasProblems(found)) create.mutate();
  };

  const refusal = (error: unknown) =>
    error && !hasProblems(problemsOf(error)) && !alreadyDone ? <Callout tone="error" role="alert">{errorMessage(i18n, error)}</Callout> : null;

  const stepper = (
    <ol className="steps" aria-label={t('setup.steps')}>
      {(['setup.step.organization', 'setup.step.admin', 'setup.step.done'] as const).map((key, index) => {
        const number = index + 1;
        return (
          <li key={key} aria-current={number === step ? 'step' : undefined} className={number < step ? 'done' : undefined}>
            <span className="n" aria-hidden="true">{number < step ? <Icon name="check" /> : number}</span>
            {t(key)}
          </li>
        );
      })}
    </ol>
  );

  const done = alreadyDone && (
    <Callout tone="warn" role="alert">
      {t('setup.alreadyDone')} <a className="link" href="/login">{t('common.signIn')}</a>
    </Callout>
  );

  if (step === 3) {
    const name = session.organization?.name ?? organizationName.trim();
    const next: [string, IconName, string, string][] = [
      ['/admin/members?open=invite', 'users', t('next.invite'), t('next.inviteHelp')],
      ['/admin/security', 'shield', t('next.security'), t('next.securityHelp')],
    ];
    return (
      <AuthLayout wide step={step}>
        <h1>{t('setup.readyTitle', { name })}</h1>
        <p className="lead">{t('setup.readyLead')}</p>
        {stepper}
        <div className="auth-panel">
          <ul className="next-list">
            {next.map(([to, icon, title, help]) => (
              <li key={to}>
                <Link to={to}>
                  <span className="cat-ico"><Icon name={icon} /></span>
                  <span><strong>{title}</strong><span className="help">{help}</span></span>
                </Link>
              </li>
            ))}
          </ul>
        </div>
        <Link className="btn btn-primary btn-lg btn-block" to="/">{t('setup.open')}</Link>
      </AuthLayout>
    );
  }

  if (step === 2) {
    return (
      <AuthLayout wide step={step}>
        <h1>{t('setup.adminTitle')}</h1>
        <p className="lead">{t('setup.adminLead')}</p>
        {stepper}
        <div className="auth-panel">
          <form className="form" ref={form} noValidate onSubmit={submitAccount}>
            {done}
            {refusal(create.error)}
            <TextField
              label={t('setup.fullName')}
              autoComplete="name"
              value={displayName}
              onChange={(event) => setDisplayName(event.target.value)}
              error={message(i18n, problems.displayName)}
            />
            <TextField
              label={t('setup.email')}
              type="email"
              autoComplete="username"
              value={email}
              onChange={(event) => setEmail(event.target.value)}
              error={message(i18n, problems.email)}
            />
            <div className="form-2">
              <TextField
                label={t('setup.password')}
                type="password"
                autoComplete="new-password"
                value={password}
                onChange={(event) => setPassword(event.target.value)}
                help={t('common.passwordHelp', { min: PASSWORD_MIN_LENGTH })}
                error={message(i18n, problems.password)}
              />
              <TextField
                label={t('setup.confirm')}
                type="password"
                autoComplete="new-password"
                value={confirmation}
                onChange={(event) => setConfirmation(event.target.value)}
                error={message(i18n, problems.confirmation)}
              />
            </div>
            <div className="row-between">
              <button type="button" className="btn btn-ghost" onClick={() => { setProblems({}); setStep(1); }}>
                {t('common.back')}
              </button>
              <button type="submit" className="btn btn-primary btn-lg" disabled={create.isPending}>
                {create.isPending && <Spinner />}
                {t('setup.create')}
              </button>
            </div>
          </form>
        </div>
      </AuthLayout>
    );
  }

  const mismatch = !fromEnvironment && !checks.url(url) && url.trim() && originOf(url) !== window.location.origin;
  return (
    <AuthLayout wide step={step}>
      <h1>{t('setup.title')}</h1>
      <p className="lead">{t('setup.lead')}</p>
      {stepper}
      <div className="auth-panel">
        <form className="form" ref={form} noValidate onSubmit={submitOrganization}>
          {done}
          {refusal(verify.error)}
          <TextField
            label={t('setup.code')}
            mono
            autoComplete="off"
            spellCheck={false}
            value={code}
            onChange={(event) => setCode(event.target.value)}
            help={<>{t('setup.codeHelp')} <code>docker compose logs lore</code></>}
            error={message(i18n, problems.setupCode)}
          />
          <TextField
            label={t('setup.orgName')}
            autoComplete="organization"
            value={organizationName}
            onChange={(event) => setOrganizationName(event.target.value)}
            error={message(i18n, problems.organizationName)}
          />
          <TextField
            label={t('setup.publicUrl')}
            type="url"
            mono
            spellCheck={false}
            value={url}
            readOnly={fromEnvironment}
            onChange={(event) => setPublicUrl(event.target.value)}
            help={fromEnvironment ? t('setup.publicUrlFromEnv') : t('setup.publicUrlHelp')}
            error={message(i18n, problems.publicUrl)}
          />
          {mismatch && <Callout tone="warn">{t('setup.publicUrlMismatch', { url: url.trim() })}</Callout>}
          <SelectField
            label={t('setup.language')}
            value={locale}
            onChange={(event) => {
              const chosen = event.target.value as Locale;
              setLocale(chosen);
              setPreview(chosen);
            }}
          >
            {LOCALES.map((option) => <option key={option} value={option} lang={option}>{LOCALE_NAMES[option]}</option>)}
          </SelectField>
          <Callout>{t('setup.noDefaultAccount')}</Callout>
          <button type="submit" className="btn btn-primary btn-lg btn-block" disabled={verify.isPending || status.isPending}>
            {verify.isPending && <Spinner />}
            {t('common.continue')}
          </button>
        </form>
      </div>
    </AuthLayout>
  );
}

function originOf(value: string): string | null {
  try {
    return new URL(value.trim()).origin;
  } catch {
    return null;
  }
}
