import { createContext, useContext, useMemo, type ReactNode } from 'react';
import { messages, type MessageKey } from './messages';

export type Locale = 'fr' | 'en';
export const LOCALES: readonly Locale[] = ['fr', 'en'];
/** Each language is named in itself, whatever the interface language. */
export const LOCALE_NAMES: Record<Locale, string> = { fr: 'Français', en: 'English' };

type Params = Record<string, string | number>;
type PluralBase = { [K in MessageKey]: K extends `${infer Base}.other` ? Base : never }[MessageKey];

export interface I18n {
  locale: Locale;
  timeZone: string;
  t(key: MessageKey, params?: Params): string;
  /** Plural form chosen by the number: tp('members.intro', 3) uses "members.intro.other". */
  tp(base: PluralBase, count: number, params?: Params): string;
  /** A duration in minutes, in the largest whole unit: "15 minutes", "1 heure", "7 jours". */
  duration(minutes: number): string;
  /** "8 nov. 2026" in the organization's time zone. */
  date(value: string | Date): string;
  /** Relative during seven days ("il y a 2 heures", "dans 23 heures"), then the date. */
  relative(value: string | Date, now?: Date): string;
}

export function toLocale(value: string | null | undefined): Locale | null {
  const code = value?.slice(0, 2).toLowerCase();
  return code === 'fr' || code === 'en' ? code : null;
}

export function browserLocale(): Locale {
  return toLocale(typeof navigator === 'undefined' ? null : navigator.language) ?? 'en';
}

export function browserTimeZone(): string {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC';
  } catch {
    return 'UTC';
  }
}

function interpolate(text: string, params: Params | undefined, locale: Locale): string {
  if (!params) return text;
  return text.replace(/\{(\w+)\}/g, (match, name: string) => {
    const value = params[name];
    if (value === undefined) return match;
    return typeof value === 'number' ? new Intl.NumberFormat(locale).format(value) : value;
  });
}

const MINUTE = 60_000;
const UNITS: [Intl.RelativeTimeFormatUnit, number][] = [
  ['day', 1440],
  ['hour', 60],
  ['minute', 1],
];

export function createI18n(locale: Locale, timeZone: string): I18n {
  const dictionary = messages[locale];
  const plurals = new Intl.PluralRules(locale);
  const relativeFormat = new Intl.RelativeTimeFormat(locale, { numeric: 'auto' });
  const safeZone = isValidTimeZone(timeZone) ? timeZone : 'UTC';
  const dateFormat = new Intl.DateTimeFormat(locale, { day: 'numeric', month: 'short', year: 'numeric', timeZone: safeZone });
  const dayFormat = new Intl.DateTimeFormat('en-CA', { year: 'numeric', month: '2-digit', day: '2-digit', timeZone: safeZone });

  const t = (key: MessageKey, params?: Params) => interpolate(dictionary[key] ?? key, params, locale);

  return {
    locale,
    timeZone: safeZone,
    t,
    tp(base, count, params) {
      const form = plurals.select(count);
      const key = (form === 'one' ? `${base}.one` : `${base}.other`) as MessageKey;
      return t(key in dictionary ? key : (`${base}.other` as MessageKey), { count, ...params });
    },
    duration(minutes) {
      const [unit, size] = UNITS.find(([, length]) => minutes >= length && minutes % length === 0) ?? ['minute', 1];
      return new Intl.NumberFormat(locale, { style: 'unit', unit, unitDisplay: 'long' }).format(minutes / size);
    },
    date(value) {
      return dateFormat.format(new Date(value));
    },
    relative(value, now = new Date()) {
      const then = new Date(value);
      const minutes = Math.round((then.getTime() - now.getTime()) / MINUTE);
      if (Math.abs(minutes) >= 7 * 1440) return dateFormat.format(then);
      if (Math.abs(minutes) < 1) return t('common.justNow');
      if (Math.abs(minutes) < 60) return relativeFormat.format(minutes, 'minute');
      if (Math.abs(minutes) < 1440) return relativeFormat.format(Math.round(minutes / 60), 'hour');
      // Whole days, counted on the calendar of the organization's time zone: "hier", "avant-hier".
      const days = Math.round((Date.parse(dayFormat.format(then)) - Date.parse(dayFormat.format(now))) / (1440 * MINUTE));
      return relativeFormat.format(days, 'day');
    },
  };
}

function isValidTimeZone(zone: string): boolean {
  try {
    new Intl.DateTimeFormat('en', { timeZone: zone });
    return true;
  } catch {
    return false;
  }
}

const I18nContext = createContext<I18n | null>(null);

export function I18nProvider({ locale, timeZone, children }: { locale: Locale; timeZone: string; children: ReactNode }) {
  const i18n = useMemo(() => createI18n(locale, timeZone), [locale, timeZone]);
  return <I18nContext value={i18n}>{children}</I18nContext>;
}

export function useI18n(): I18n {
  const i18n = useContext(I18nContext);
  if (!i18n) throw new Error('useI18n outside I18nProvider');
  return i18n;
}
