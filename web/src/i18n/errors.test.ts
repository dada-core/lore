import { describe, expect, it } from 'vitest';
import { ApiError } from '../api/client';
import { errorMessage } from './errors';
import { createI18n } from './i18n';

describe('errorMessage', () => {
  it('says how long a block lasts, also when the current password was asked in my account', () => {
    const fr = createI18n('fr', 'Europe/Paris');
    const locked = new ApiError(429, 'login_locked', [], { status: 429, code: 'login_locked', retryAfterMinutes: 15 });

    const message = errorMessage(fr, locked);

    expect(message).toBe(fr.t('login.locked', { duration: fr.duration(15) }));
    expect(message).toContain('15 minutes');
  });

  it('says the server is busy, to try again shortly, and gives one message for any other failure', () => {
    const en = createI18n('en', 'Europe/Paris');
    expect(errorMessage(en, new ApiError(503, 'server_busy'))).toBe(en.t('error.server_busy'));
    expect(errorMessage(en, new ApiError(503, 'error'))).toBe(en.t('error.server_error'));
    expect(errorMessage(en, new ApiError(500, 'database'))).toBe(en.t('error.server_error'));
  });
});
