import { afterEach, describe, expect, it, vi } from 'vitest';
import { api, call, setUnauthenticatedHandler } from '../api/client';
import { rememberSearch } from '../search/search';
import { fakeServer, json } from '../test/server';
import { expireSession, safeNext } from './session';

describe('safeNext', () => {
  it('keeps a path of this site', () => {
    expect(safeNext('/admin/members?open=invite')).toBe('/admin/members?open=invite');
  });

  it('never leaves the site', () => {
    expect(safeNext('https://evil.invalid')).toBe('/');
    expect(safeNext('//evil.invalid')).toBe('/');
    expect(safeNext('/\\evil.invalid')).toBe('/');
    expect(safeNext(null)).toBe('/');
  });

  it('does not come back to the sign-in page', () => {
    expect(safeNext('/login?next=/account')).toBe('/');
  });
});

describe('expireSession', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    setUnauthenticatedHandler(() => {});
    localStorage.clear();
  });

  it('forgets the recent searches of this browser when the server answers 401', async () => {
    rememberSearch('0193b1f2-0000-7000-8000-000000000001', 'restaurer la base');
    rememberSearch('0193b1f2-0000-7000-8000-000000000002', 'certificats');
    localStorage.setItem('lore.language', 'fr');
    setUnauthenticatedHandler(expireSession);
    fakeServer({ 'GET /api/v1/account': () => json({ status: 401, code: 'unauthenticated' }, 401) });

    await expect(call(() => api.GET('/api/v1/account'))).rejects.toMatchObject({ status: 401 });

    expect(Object.keys(localStorage).filter((key) => key.startsWith('lore.searches.'))).toEqual([]);
    expect(localStorage.getItem('lore.language')).toBe('fr');
  });
});
