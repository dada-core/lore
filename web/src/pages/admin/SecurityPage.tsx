import { useRef, useState, type FormEvent } from 'react';
import { errorMessage } from '../../i18n/errors';
import { useI18n } from '../../i18n/i18n';
import { SettingsView } from '../../layouts/SettingsView';
import { Callout, hasProblems, message, problemsOf, SettingRow, useFocusFirstProblem, validate, type Problem, type Problems } from '../../ui/forms';
import { Spinner } from '../../ui/Icon';
import { useNotify } from '../../ui/Toast';
import { changesOf, useOrganizationSettings, useSaveSettings, type Settings } from './useOrganizationSettings';

const LOCKOUT_MINUTES = [5, 15, 30, 60, 240, 1440];
const SESSION_MINUTES = [15, 30, 60, 120, 240, 480, 1440, 10080];
const INVITATION_HOURS = [24, 48, 168, 336, 720];

/** Administration › Security and sign-in: password length, lockout, sessions, invitation links. */
export function SecurityPage() {
  const i18n = useI18n();
  const settings = useOrganizationSettings();
  return (
    <SettingsView section="security" title={i18n.t('admin.security')}>
      {settings.data ? (
        <SecurityForm settings={settings.data} />
      ) : settings.isError ? (
        <div className="set-sec"><Callout tone="error" role="alert">{errorMessage(i18n, settings.error)}</Callout></div>
      ) : (
        <div className="set-sec" role="status"><Spinner /><span className="sr-only">{i18n.t('common.loading')}</span></div>
      )}
    </SettingsView>
  );
}

function withCurrent(values: number[], current: number): number[] {
  return Array.from(new Set([...values, current])).sort((a, b) => a - b);
}

function range(value: string, min: number, max: number): Problem | null {
  const number = Number(value);
  if (!value.trim()) return { code: 'required' };
  return Number.isInteger(number) && number >= min && number <= max ? null : { code: 'out_of_range', params: { min, max } };
}

function SecurityForm({ settings }: { settings: Settings }) {
  const i18n = useI18n();
  const { t } = i18n;
  const notify = useNotify();
  const [minLength, setMinLength] = useState(String(settings.passwordMinLength));
  const [maxAttempts, setMaxAttempts] = useState(String(settings.loginMaxAttempts));
  const [lockout, setLockout] = useState(settings.loginLockoutMinutes);
  const [sessionTimeout, setSessionTimeout] = useState(settings.sessionTimeoutMinutes);
  const [invitationHours, setInvitationHours] = useState(settings.invitationValidityHours);
  const [problems, setProblems] = useState<Problems>({});
  const form = useRef<HTMLFormElement>(null);
  useFocusFirstProblem(form, problems);

  const save = useSaveSettings(() => notify(t('common.saved')), (error) => setProblems(problemsOf(error)));

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const found = validate({
      passwordMinLength: range(minLength, 8, 64),
      loginMaxAttempts: range(maxAttempts, 3, 20),
    });
    setProblems(found);
    if (hasProblems(found)) return;
    save.mutate(changesOf<Settings>(settings, {
      passwordMinLength: Number(minLength),
      loginMaxAttempts: Number(maxAttempts),
      loginLockoutMinutes: lockout,
      sessionTimeoutMinutes: sessionTimeout,
      invitationValidityHours: invitationHours,
    }));
  };

  const ceiling = settings.sessionTimeoutMaxMinutes;
  const sessionOptions = withCurrent(SESSION_MINUTES.filter((minutes) => minutes <= ceiling), settings.sessionTimeoutMinutes);
  const limited = ceiling < Math.max(...SESSION_MINUTES);

  return (
    <form className="set-sec" ref={form} noValidate onSubmit={submit} aria-labelledby="security-passwords">
      <h2 id="security-passwords">{t('security.passwords')}</h2>
      <p className="help">{t('security.appliesNext')}</p>
      {save.isError && !hasProblems(problemsOf(save.error)) && (
        <div style={{ marginTop: 12 }}><Callout tone="error" role="alert">{errorMessage(i18n, save.error)}</Callout></div>
      )}
      <div className="set-box">
        <SettingRow label={t('security.minLength')} help={t('security.minLengthHelp')} error={message(i18n, problems.passwordMinLength)}>
          {(ids) => (
            <div className="ctl-line">
              <input className="input input-num" type="number" inputMode="numeric" min={8} max={64} value={minLength} onChange={(event) => setMinLength(event.target.value)} {...ids} />
              <span className="help">{t('security.characters')}</span>
            </div>
          )}
        </SettingRow>
        <SettingRow label={t('security.maxAttempts')} help={t('security.maxAttemptsHelp')} error={message(i18n, problems.loginMaxAttempts)}>
          {(ids) => (
            <input className="input input-num" type="number" inputMode="numeric" min={3} max={20} value={maxAttempts} onChange={(event) => setMaxAttempts(event.target.value)} {...ids} />
          )}
        </SettingRow>
        <SettingRow label={t('security.lockout')} error={message(i18n, problems.loginLockoutMinutes)}>
          {(ids) => (
            <select className="select" value={lockout} onChange={(event) => setLockout(Number(event.target.value))} {...ids}>
              {withCurrent(LOCKOUT_MINUTES, settings.loginLockoutMinutes).map((minutes) => (
                <option key={minutes} value={minutes}>{i18n.duration(minutes)}</option>
              ))}
            </select>
          )}
        </SettingRow>
        <SettingRow
          label={t('security.sessionTimeout')}
          help={limited ? t('security.sessionCeiling', { duration: i18n.duration(ceiling) }) : undefined}
          error={message(i18n, problems.sessionTimeoutMinutes)}
        >
          {(ids) => (
            <select className="select" value={sessionTimeout} onChange={(event) => setSessionTimeout(Number(event.target.value))} {...ids}>
              {sessionOptions.map((minutes) => <option key={minutes} value={minutes}>{i18n.duration(minutes)}</option>)}
            </select>
          )}
        </SettingRow>
        <SettingRow label={t('security.invitationValidity')} error={message(i18n, problems.invitationValidityHours)}>
          {(ids) => (
            <select className="select" value={invitationHours} onChange={(event) => setInvitationHours(Number(event.target.value))} {...ids}>
              {withCurrent(INVITATION_HOURS, settings.invitationValidityHours).map((hours) => (
                <option key={hours} value={hours}>{i18n.duration(hours * 60)}</option>
              ))}
            </select>
          )}
        </SettingRow>
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
