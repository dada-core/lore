import { vi } from 'vitest';

/**
 * A fake XMLHttpRequest for the uploads of the import window: each request sent is kept, and the
 * test decides what happens to it (progress, answer, network failure).
 */
export class FakeRequest {
  method = '';
  url = '';
  readonly headers: Record<string, string> = {};
  body: FormData | null = null;
  status = 0;
  responseText = '';
  aborted = false;
  finished = false;
  readonly upload: { onprogress: ((event: ProgressEvent) => void) | null } = { onprogress: null };
  onload: (() => void) | null = null;
  onerror: (() => void) | null = null;
  ontimeout: (() => void) | null = null;
  onabort: (() => void) | null = null;

  open(method: string, url: string): void {
    this.method = method;
    this.url = url;
  }

  setRequestHeader(name: string, value: string): void {
    this.headers[name] = value;
  }

  send(body: FormData): void {
    this.body = body;
    FakeRequest.sent.push(this);
  }

  abort(): void {
    this.aborted = true;
    this.finished = true;
    this.onabort?.();
  }

  /** The name of the file sent. */
  get filename(): string {
    return (this.body?.get('file') as File | null)?.name ?? '';
  }

  /** The values of a field of the form. */
  field(name: string): string[] {
    return (this.body?.getAll(name) ?? []).map((value) => (typeof value === 'string' ? value : value.name));
  }

  progress(loaded: number, total: number): void {
    this.upload.onprogress?.({ lengthComputable: true, loaded, total } as ProgressEvent);
  }

  respond(status: number, body: unknown): void {
    this.status = status;
    this.responseText = JSON.stringify(body);
    this.finished = true;
    this.onload?.();
  }

  /** An answer that is not JSON: a page sent by a proxy, for example. */
  respondText(status: number, text: string): void {
    this.status = status;
    this.responseText = text;
    this.finished = true;
    this.onload?.();
  }

  /** The connection is lost. */
  fail(): void {
    this.finished = true;
    this.onerror?.();
  }

  static sent: FakeRequest[] = [];
}

/** Replaces XMLHttpRequest; returns the requests sent, in order. */
export function fakeUploads() {
  FakeRequest.sent = [];
  vi.stubGlobal('XMLHttpRequest', FakeRequest);
  return {
    get sent(): FakeRequest[] {
      return FakeRequest.sent;
    },
    /** The requests still waiting for an answer. */
    get pending(): FakeRequest[] {
      return FakeRequest.sent.filter((request) => !request.finished);
    },
  };
}
