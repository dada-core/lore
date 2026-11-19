import { api, ApiError, csrfToken, reportUnauthenticated, toApiError, tryOtherCsrfCookie, type Schemas } from '../api/client';

/** Where an imported file goes, and who sees it: chosen in the window before it is dropped. */
export interface ImportFields {
  folderId: string | null;
  status: string;
  category: string | null;
  restricted: boolean;
  userIds: string[];
}

export interface Upload {
  /**
   * The new document, or an {@link ApiError} (status 0 when the server could not be reached or
   * did not answer as Lore); an AbortError once {@link abort} is called.
   */
  done: Promise<Schemas['DocumentView']>;
  abort: () => void;
}

interface Answer {
  status: number;
  body: unknown;
}

function parse(text: string): unknown {
  try {
    return text ? JSON.parse(text) : null;
  } catch {
    return null;
  }
}

/** A document as the server describes it: a page sent by a proxy instead is not one. */
function isDocument(body: unknown): body is Schemas['DocumentView'] {
  if (typeof body !== 'object' || body === null) return false;
  const document = body as Record<string, unknown>;
  const version = document.currentVersion as Record<string, unknown> | null | undefined;
  return typeof document.id === 'string' && typeof document.title === 'string'
    && typeof version === 'object' && version !== null
    && typeof version.extractionStatus === 'string' && typeof version.mediaType === 'string'
    && typeof document.actions === 'object' && document.actions !== null;
}

function aborted(): DOMException {
  return new DOMException('The upload was stopped', 'AbortError');
}

function form(file: File, fields: ImportFields): FormData {
  const data = new FormData();
  if (fields.folderId) data.append('folderId', fields.folderId);
  data.append('status', fields.status);
  if (fields.category) data.append('category', fields.category);
  if (fields.restricted) {
    data.append('restricted', 'true');
    // One field, the identifiers separated by commas: a form holds at most 50 fields.
    data.append('userIds', fields.userIds.join(','));
  }
  data.append('file', file, file.name);
  return data;
}

/**
 * Sends one file to a space with XMLHttpRequest, the only way to follow the progress of what is
 * sent. As with call(), the CSRF token of its cookie goes along, and a request refused
 * for a missing token is sent again once, after fetching a new one; with the token of the other
 * cookie if it is refused again while both are there.
 */
export function uploadFile(spaceId: string, file: File, fields: ImportFields, onProgress: (fraction: number) => void): Upload {
  return post(`/api/v1/spaces/${encodeURIComponent(spaceId)}/documents`, form(file, fields), onProgress);
}

/**
 * Sends the file of a new version of a document, with what changes and the status it asks for
 * (none: unchanged), the same way.
 */
export function uploadVersion(documentId: string, file: File, note: string, status: string | null,
  onProgress: (fraction: number) => void): Upload {
  const data = new FormData();
  if (note.trim()) data.append('note', note.trim());
  if (status) data.append('status', status);
  data.append('file', file, file.name);
  return post(`/api/v1/documents/${encodeURIComponent(documentId)}/versions`, data, onProgress);
}

function post(url: string, data: FormData, onProgress: (fraction: number) => void): Upload {
  let request: XMLHttpRequest | null = null;
  let stopped = false;

  const send = () => new Promise<Answer>((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    request = xhr;
    xhr.open('POST', url);
    xhr.setRequestHeader('Accept', 'application/json');
    const token = csrfToken();
    if (token) xhr.setRequestHeader('X-XSRF-TOKEN', token);
    xhr.upload.onprogress = (event) => {
      if (event.lengthComputable && event.total > 0) onProgress(event.loaded / event.total);
    };
    xhr.onload = () => resolve({ status: xhr.status, body: parse(xhr.responseText) });
    xhr.onerror = () => reject(new ApiError(0, 'network'));
    xhr.ontimeout = () => reject(new ApiError(0, 'network'));
    xhr.onabort = () => reject(aborted());
    xhr.send(data);
  });

  const refusedForCsrf = (answer: Answer) => answer.status === 403 && toApiError(403, answer.body).code === 'csrf';

  const done = (async () => {
    let answer = await send();
    if (refusedForCsrf(answer)) {
      try {
        await api.GET('/api/v1/session');
      } catch {
        // The second attempt says what happened.
      }
      if (stopped) throw aborted();
      onProgress(0);
      answer = await send();
      const restore = refusedForCsrf(answer) ? tryOtherCsrfCookie() : null;
      if (restore) {
        if (stopped) throw aborted();
        onProgress(0);
        answer = await send();
        if (refusedForCsrf(answer)) restore();
      }
    }
    if (answer.status >= 200 && answer.status < 300) {
      // Something answered instead of Lore (a proxy, a sign-in page): the file may not have arrived.
      if (!isDocument(answer.body)) throw new ApiError(0, 'unexpected_response');
      return answer.body;
    }
    const error = toApiError(answer.status, answer.body);
    if (error.status === 401 && error.code === 'unauthenticated') reportUnauthenticated();
    throw error;
  })();

  return {
    done,
    abort: () => {
      stopped = true;
      request?.abort();
    },
  };
}
