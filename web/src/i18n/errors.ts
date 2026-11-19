import { ApiError, type FieldError } from '../api/client';
import type { I18n } from './i18n';
import { messages, type MessageKey } from './messages';

function isKey(key: string): key is MessageKey {
  return key in messages.en;
}

/** The message shown under a field, for a field error sent by the server or found in the browser. */
export function fieldMessage(i18n: I18n, error: Pick<FieldError, 'code' | 'params'>): string {
  const key = `field.${error.code}`;
  return isKey(key) ? i18n.t(key, error.params) : i18n.t('field.unknown');
}

/** The message for an error that concerns no particular field. */
export function errorMessage(i18n: I18n, error: unknown): string {
  if (!(error instanceof ApiError)) return i18n.t('error.generic');
  if (error.status === 0) return i18n.t('error.network');
  // A search that took too long, or a server too busy to check a password, says so; any other
  // failure of the server is the same to the person.
  if (error.status >= 500 && error.code !== 'search_timeout' && error.code !== 'server_busy') {
    return i18n.t('error.server_error');
  }
  // Too many wrong passwords, at sign-in or when the current password is asked again (my account).
  if (error.code === 'login_locked') {
    return i18n.t('login.locked', { duration: i18n.duration(error.number('retryAfterMinutes') ?? 15) });
  }
  const key = `error.${error.code}`;
  return isKey(key) ? i18n.t(key) : i18n.t('error.generic');
}
