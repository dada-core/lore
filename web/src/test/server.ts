import { vi } from 'vitest';

type Handler = (request: Request) => Response | Promise<Response>;

export function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': status >= 400 ? 'application/problem+json' : 'application/json' },
  });
}

export function noContent(): Response {
  return new Response(null, { status: 204 });
}

/**
 * A fake Lore server for the interface's tests: "METHOD /path" mapped to a handler. Every
 * request is recorded; an unexpected one fails with 404.
 */
export function fakeServer(routes: Record<string, Handler>) {
  const requests: Request[] = [];
  const fetch = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const request = input instanceof Request ? input : new Request(input, init);
    requests.push(request.clone());
    const handler = routes[`${request.method} ${new URL(request.url).pathname}`];
    return handler ? handler(request) : json({ status: 404, code: 'not_found' }, 404);
  });
  vi.stubGlobal('fetch', fetch);
  return { fetch, requests };
}

export const organization = { name: 'DadaGroup', defaultLocale: 'fr', timeZone: 'Europe/Paris' };
export const amenan = { id: '0193b1f2-0000-7000-8000-000000000001', displayName: 'Amenan Souaga', email: 'amenan.souaga@dada.group', locale: 'fr', role: 'ADMIN' };
