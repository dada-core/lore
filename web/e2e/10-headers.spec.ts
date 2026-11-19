import { request } from '@playwright/test';
import { expect, PASSWORD, PEOPLE, test } from './lore';

// The security headers of the image as it runs, which the server tests check on the code. The
// Secure attribute and the __Host- names, with a public address in https, are left to the server
// tests (CookieNamesTests): this installation runs in http.
test('The pages and the API answer with their security headers', async ({ baseURL }) => {
  const client = await request.newContext({ baseURL });
  try {
    const page = await client.get('/login');
    expect(page.status()).toBe(200);
    const headers = page.headers();
    expect(headers['content-security-policy']).toContain("script-src 'self'");
    expect(headers['content-security-policy']).toContain("frame-ancestors 'none'");
    expect(headers['x-frame-options']).toBe('DENY');
    expect(headers['x-content-type-options']).toBe('nosniff');
    expect(headers['referrer-policy']).toBe('same-origin');

    await client.get('/api/v1/session');
    const cookies = async () => (await client.storageState()).cookies;
    const token = (await cookies()).find((cookie) => cookie.name === 'XSRF-TOKEN');
    // Read by the interface, which sends it back: not HttpOnly.
    expect(token).toMatchObject({ httpOnly: false, sameSite: 'Lax' });

    const signedIn = await client.post('/api/v1/auth/login', {
      data: { email: PEOPLE.wilfried.email, password: PASSWORD },
      headers: { 'X-XSRF-TOKEN': token!.value },
    });
    expect(signedIn.status()).toBe(200);
    expect((await cookies()).find((cookie) => cookie.name === 'LORE_SESSION')).toMatchObject({ httpOnly: true, sameSite: 'Lax' });
    expect(signedIn.headers()['cache-control']).toMatch(/no-store/);
  } finally {
    await client.dispose();
  }
});
