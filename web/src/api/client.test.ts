import { afterEach, describe, expect, it, vi } from 'vitest';
import { fakeServer, json, noContent } from '../test/server';
import { api, ApiError, call, setUnauthenticatedHandler } from './client';

afterEach(() => {
  vi.unstubAllGlobals();
  setUnauthenticatedHandler(() => {});
});

describe('call', () => {
  it('sends the CSRF token on a request that changes something, never on a read', async () => {
    document.cookie = 'XSRF-TOKEN=token-123; path=/';
    const server = fakeServer({
      'GET /api/v1/session': () => json({ setupRequired: false, organization: null, user: null }),
      'POST /api/v1/auth/logout': () => noContent(),
    });
    await call(() => api.GET('/api/v1/session'));
    await call(() => api.POST('/api/v1/auth/logout'));
    expect(server.requests[0].headers.get('X-XSRF-TOKEN')).toBeNull();
    expect(server.requests[1].headers.get('X-XSRF-TOKEN')).toBe('token-123');
  });

  it('turns a problem into an ApiError with its code, fields and details', async () => {
    fakeServer({
      'POST /api/v1/auth/login': () => json({ status: 401, code: 'invalid_credentials', remainingAttempts: 2, lockoutMinutes: 15 }, 401),
      'POST /api/v1/setup': () => json({
        status: 400,
        code: 'invalid_request',
        errors: [{ field: 'password', code: 'too_short', params: { min: 12 } }],
      }, 400),
    });
    const login = await call(() => api.POST('/api/v1/auth/login', { body: { email: 'a@b.c', password: 'x' } })).catch((error: unknown) => error);
    expect(login).toBeInstanceOf(ApiError);
    expect((login as ApiError).code).toBe('invalid_credentials');
    expect((login as ApiError).number('remainingAttempts')).toBe(2);

    const setup = await call(() => api.POST('/api/v1/setup', {
      body: { setupCode: 'c', organizationName: 'o', displayName: 'd', email: 'e@f.g', password: 'short' },
    })).catch((error: unknown) => error);
    expect((setup as ApiError).fieldErrors).toEqual([{ field: 'password', code: 'too_short', params: { min: 12 } }]);
  });

  it('reports an expired session', async () => {
    const expired = vi.fn();
    setUnauthenticatedHandler(expired);
    fakeServer({ 'GET /api/v1/account': () => json({ status: 401, code: 'unauthenticated' }, 401) });
    await expect(call(() => api.GET('/api/v1/account'))).rejects.toMatchObject({ status: 401, code: 'unauthenticated' });
    expect(expired).toHaveBeenCalledOnce();
  });

  it('fetches a new CSRF token and sends the request again, once', async () => {
    let attempts = 0;
    const server = fakeServer({
      'GET /api/v1/session': () => {
        document.cookie = 'XSRF-TOKEN=fresh; path=/';
        return json({ setupRequired: false, organization: null, user: null });
      },
      'POST /api/v1/auth/logout': () => (++attempts === 1 ? json({ status: 403, code: 'csrf' }, 403) : noContent()),
    });
    await call(() => api.POST('/api/v1/auth/logout'));
    expect(attempts).toBe(2);
    expect(server.requests.at(-1)?.headers.get('X-XSRF-TOKEN')).toBe('fresh');
  });

  it('says when the server does not answer', async () => {
    vi.stubGlobal('fetch', vi.fn(() => Promise.reject(new TypeError('Failed to fetch'))));
    await expect(call(() => api.GET('/api/v1/session'))).rejects.toMatchObject({ status: 0, code: 'network' });
  });
});
