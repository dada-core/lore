import { useMemo, useRef, useState, type FormEvent } from 'react';
import { errorMessage } from '../../i18n/errors';
import { LOCALE_NAMES, LOCALES, useI18n } from '../../i18n/i18n';
import { SettingsView } from '../../layouts/SettingsView';
import { Callout, checks, hasProblems, message, problemsOf, SettingRow, useFocusFirstProblem, validate, type Problems } from '../../ui/forms';
import { Spinner } from '../../ui/Icon';
import { useNotify } from '../../ui/Toast';
import { changesOf, useOrganizationSettings, useSaveSettings, type Settings } from './useOrganizationSettings';

/** Administration › General: name, public address, default language, time zone. */
export function GeneralPage() {
  const i18n = useI18n();
  const settings = useOrganizationSettings();
  return (
    <SettingsView section="general" title={i18n.t('admin.general')}>
      {settings.data ? (
        <GeneralForm settings={settings.data} />
      ) : settings.isError ? (
        <div className="set-sec"><Callout tone="error" role="alert">{errorMessage(i18n, settings.error)}</Callout></div>
      ) : (
        <div className="set-sec" role="status"><Spinner /><span className="sr-only">{i18n.t('common.loading')}</span></div>
      )}
    </SettingsView>
  );
}

function supportedZones(): string[] {
  try {
    return Intl.supportedValuesOf('timeZone');
  } catch {
    return [];
  }
}

function timeZones(current: string): string[] {
  return Array.from(new Set(['UTC', current, ...supportedZones()]))
    .sort((a, b) => (a === 'UTC' ? -1 : b === 'UTC' ? 1 : a.localeCompare(b)));
}

function GeneralForm({ settings }: { settings: Settings }) {
  const i18n = useI18n();
  const { t } = i18n;
  const notify = useNotify();
  const [name, setName] = useState(settings.name);
  const [publicUrl, setPublicUrl] = useState(settings.publicUrl);
  const [defaultLocale, setDefaultLocale] = useState(settings.defaultLocale);
  const [timeZone, setTimeZone] = useState(settings.timeZone);
  const [problems, setProblems] = useState<Problems>({});
  const form = useRef<HTMLFormElement>(null);
  const zones = useMemo(() => timeZones(settings.timeZone), [settings.timeZone]);
  useFocusFirstProblem(form, problems);

  const save = useSaveSettings(
    (saved) => {
      setPublicUrl(saved.publicUrl);
      notify(t('common.saved'));
    },
    (error) => setProblems(problemsOf(error)),
  );

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const found = validate({
      name: checks.required(name),
      publicUrl: settings.publicUrlFromEnvironment ? null : checks.url(publicUrl),
    });
    setProblems(found);
    if (hasProblems(found)) return;
    const edited = { name: name.trim(), defaultLocale, timeZone, ...(settings.publicUrlFromEnvironment ? {} : { publicUrl: publicUrl.trim() }) };
    save.mutate(changesOf<Settings>(settings, edited));
  };

  return (
    <form className="set-sec" ref={form} noValidate onSubmit={submit} aria-labelledby="general-organization">
      <h2 id="general-organization">{t('general.organization')}</h2>
      {save.isError && !hasProblems(problemsOf(save.error)) && (
        <div style={{ marginTop: 12 }}><Callout tone="error" role="alert">{errorMessage(i18n, save.error)}</Callout></div>
      )}
      <div className="set-box">
        <SettingRow label={t('general.name')} error={message(i18n, problems.name)}>
          {(ids) => <input className="input" autoComplete="organization" value={name} onChange={(event) => setName(event.target.value)} {...ids} />}
        </SettingRow>
        <SettingRow
          label={t('general.publicUrl')}
          help={settings.publicUrlFromEnvironment ? t('general.publicUrlEnv') : t('general.publicUrlHelp')}
          error={message(i18n, problems.publicUrl)}
        >
          {(ids) => (
            <input
              className="input input-mono"
              type="url"
              spellCheck={false}
              value={publicUrl}
              readOnly={settings.publicUrlFromEnvironment}
              onChange={(event) => setPublicUrl(event.target.value)}
              {...ids}
            />
          )}
        </SettingRow>
        <SettingRow label={t('general.defaultLocale')} help={t('general.defaultLocaleHelp')} error={message(i18n, problems.defaultLocale)}>
          {(ids) => (
            <select className="select" value={defaultLocale} onChange={(event) => setDefaultLocale(event.target.value)} {...ids}>
              {LOCALES.map((locale) => <option key={locale} value={locale} lang={locale}>{LOCALE_NAMES[locale]}</option>)}
            </select>
          )}
        </SettingRow>
        <SettingRow label={t('general.timeZone')} help={t('general.timeZoneHelp')} error={message(i18n, problems.timeZone)}>
          {(ids) => (
            <select className="select" value={timeZone} onChange={(event) => setTimeZone(event.target.value)} {...ids}>
              {zones.map((zone) => <option key={zone} value={zone}>{zone.replace(/_/g, ' ')}</option>)}
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
