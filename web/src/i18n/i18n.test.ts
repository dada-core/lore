import { describe, expect, it } from 'vitest';
import { createI18n } from './i18n';
import { frenchTypography, messages } from './messages';

const EM_DASH = String.fromCharCode(0x2014);

describe('messages', () => {
  it('has every text in both languages, without an em dash', () => {
    expect(Object.keys(messages.fr).sort()).toEqual(Object.keys(messages.en).sort());
    for (const locale of ['fr', 'en'] as const) {
      for (const [key, text] of Object.entries(messages[locale])) {
        expect(text, `${locale} ${key}`).not.toBe('');
        expect(text, `${locale} ${key}`).not.toContain(EM_DASH);
      }
    }
  });

  it('uses the same placeholders in both languages', () => {
    const placeholders = (text: string) => (text.match(/\{\w+\}/g) ?? []).sort();
    for (const key of Object.keys(messages.fr) as (keyof typeof messages.fr)[]) {
      expect(placeholders(messages.en[key]), key).toEqual(placeholders(messages.fr[key]));
    }
  });

  it('applies French typography', () => {
    expect(frenchTypography('Rôle mis à jour : Membre')).toBe('Rôle mis à jour : Membre');
    expect(frenchTypography('Mot de passe oublié ?')).toBe('Mot de passe oublié ?');
    expect(frenchTypography('« test »')).toBe('« test »');
    expect(messages.fr['login.forgot']).toBe('Mot de passe oublié ?');
  });
});

describe('createI18n', () => {
  const now = new Date('2026-11-12T15:00:00Z');

  it('interpolates and chooses the plural form', () => {
    const fr = createI18n('fr', 'Europe/Paris');
    const en = createI18n('en', 'Europe/Paris');
    expect(fr.tp('members.intro', 1)).toMatch(/^1 membre\./);
    expect(fr.tp('members.intro', 48)).toMatch(/^48 membres\./);
    expect(en.tp('members.intro', 1)).toMatch(/^1 member\./);
    expect(en.tp('members.intro', 2)).toMatch(/^2 members\./);
    // No "de {name}": "de Amenan" would be wrong French, "d'Amenan" is what the reader expects.
    expect(fr.t('reset.account', { name: 'Amenan Souaga', email: 'amenan.souaga@dada.group' }))
      .toBe('Compte : Amenan Souaga, amenan.souaga@dada.group');
    for (const [key, text] of Object.entries(messages.fr)) {
      expect(text, key).not.toMatch(/\bde \{name\}/);
    }
  });

  it('writes durations in their largest whole unit', () => {
    // Intl separates the number from the unit with a no-break space, as French typography wants.
    const fr = createI18n('fr', 'UTC');
    const plain = (minutes: number) => fr.duration(minutes).replace(/\s/g, ' ');
    expect(plain(15)).toBe('15 minutes');
    expect(plain(60)).toBe('1 heure');
    expect(plain(480)).toBe('8 heures');
    expect(plain(10080)).toBe('7 jours');
    expect(createI18n('en', 'UTC').duration(1440)).toBe('1 day');
  });

  it('writes recent dates relatively, older ones as dates', () => {
    const fr = createI18n('fr', 'Europe/Paris');
    expect(fr.relative('2026-11-12T13:00:00Z', now)).toBe('il y a 2 heures');
    expect(fr.relative('2026-11-11T10:00:00Z', now)).toBe('hier');
    expect(fr.relative('2026-11-13T14:00:00Z', now)).toBe('dans 23 heures');
    expect(fr.relative('2026-11-01T10:00:00Z', now)).toBe('1 nov. 2026');
    expect(createI18n('en', 'UTC').relative('2026-11-12T14:59:40Z', now)).toBe('just now');
  });

  it('falls back to UTC for an unknown time zone', () => {
    expect(createI18n('fr', 'Mars/Olympus').timeZone).toBe('UTC');
  });
});
