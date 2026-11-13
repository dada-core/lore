import createClient from 'openapi-fetch';
import type { components, paths } from './schema';

export type Schemas = components['schemas'];
export type SessionInfo = Schemas['SessionInfo'];
export type CurrentUser = Schemas['CurrentUser'];
export type Role = 'OWNER' | 'ADMIN' | 'MEMBER';

/** A field to correct, as sent by the server: {"field": "password", "code": "too_short", "params": {"min": 12}}. */
export interface FieldError {
  field: string;
  code: string;
  params: Record<string, string | number>;
}

/**
 * An API error. {@link code} is the stable identifier sent by the server ("invalid_credentials",
 * "last_owner"...), translated by the interface; {@link status} is 0 when the server did not answer.
 */
export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly fieldErrors: FieldError[];
  readonly details: Record<string, unknown>;

  constructor(status: number, code: string, fieldErrors: FieldError[] = [], details: Record<string, unknown> = {}) {
    super(code);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
    this.fieldErrors = fieldErrors;
    this.details = details;
  }

  number(name: string): number | undefined {
    const value = this.details[name];
    return typeof value === 'number' ? value : undefined;
  }
}

const SAFE_METHODS = new Set(['GET', 'HEAD', 'OPTIONS']);

export function readCookie(name: string): string | null {
  for (const part of document.cookie.split(';')) {
    const [key, ...rest] = part.trim().split('=');
    if (key === name) return decodeURIComponent(rest.join('='));
  }
  return null;
}

export const api = createClient<paths>({
  baseUrl: typeof window === 'undefined' ? '' : window.location.origin,
  // Looked up on every call, so that tests can replace it.
  fetch: (request) => globalThis.fetch(request),
});

// Every request that changes something sends back the CSRF token of the XSRF-TOKEN cookie.
api.use({
  onRequest({ request }) {
    if (!SAFE_METHODS.has(request.method)) {
      const token = readCookie('XSRF-TOKEN');
      if (token) request.headers.set('X-XSRF-TOKEN', token);
    }
    return request;
  },
});

let onUnauthenticated: () => void = () => {};

/** Called when the server answers 401: the session has expired or was closed elsewhere. */
export function setUnauthenticatedHandler(handler: () => void): void {
  onUnauthenticated = handler;
}

interface Result<T> {
  data?: T;
  error?: unknown;
  response: Response;
}

async function send<T>(request: () => Promise<Result<T>>): Promise<Result<T>> {
  try {
    return await request();
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') throw error;
    throw new ApiError(0, 'network');
  }
}

function toApiError(status: number, body: unknown): ApiError {
  const problem = typeof body === 'object' && body !== null ? (body as Record<string, unknown>) : {};
  const code = typeof problem.code === 'string' ? problem.code : status >= 500 ? 'server_error' : 'error';
  const fieldErrors = Array.isArray(problem.errors)
    ? (problem.errors as FieldError[]).map((error) => ({ ...error, params: error.params ?? {} }))
    : [];
  return new ApiError(status, code, fieldErrors, problem);
}

/**
 * Sends a request and returns its data, or throws an {@link ApiError}. A request refused for a
 * missing CSRF token is sent again once, after fetching a new token.
 */
export async function call<T>(request: () => Promise<Result<T>>): Promise<T> {
  let result = await send(request);
  if (result.response.status === 403 && toApiError(403, result.error).code === 'csrf') {
    await send(() => api.GET('/api/v1/session'));
    result = await send(request);
  }
  if (result.response.ok) return result.data as T;
  const error = toApiError(result.response.status, result.error);
  if (error.status === 401 && error.code === 'unauthenticated') onUnauthenticated();
  throw error;
}
