import { api, ApiError, call, type Schemas } from '../api/client';
import type { Problem } from '../ui/forms';
import { extensionOf, timing, type DocumentItem, type ImportLimits } from './documents';
import { uploadFile, type ImportFields, type Upload } from './upload';

/** Files sent at the same time. */
export const PARALLEL = 5;
/** Documents followed per request, as the server allows. */
const FOLLOWED_PER_REQUEST = 100;
/** The announcements kept for screen readers: the last ones are enough. */
const NEWS_KEPT = 5;

/** What the window shows of an imported document while its text is extracted. */
export interface Followed {
  id: string;
  title: string;
  mediaType: string;
  extractionStatus: string;
  extractionError?: string | null;
  pageCount?: number | null;
  sectionCount?: number | null;
}

/**
 * The life of a file in the window: refused before or by the server, waiting for its turn,
 * being sent, interrupted, then imported and followed; a duplicate can be removed, and a
 * document deleted elsewhere in the meantime is gone.
 */
export type LineState = 'refused' | 'waiting' | 'sending' | 'interrupted' | 'imported' | 'removed' | 'gone';

export interface Line {
  key: number;
  file: File;
  spaceId: string;
  fields: ImportFields;
  state: LineState;
  /** From 0 to 100, while it is sent; 100 once the whole file has left. */
  percent: number;
  /** Refused before sending: the check that failed. */
  problem?: Problem;
  /** Refused by the server: its answer. */
  error?: ApiError;
  /** The size limit of the organization when the file was dropped, in MB. */
  maxMegabytes?: number;
  document?: Followed;
  duplicateOf?: { id: string; title: string } | null;
  canDelete?: boolean;
  /** A new attempt at the extraction, or the deletion of a duplicate, in progress. */
  busy?: boolean;
  /** Why that attempt or deletion failed. */
  actionError?: unknown;
}

/** A line as it was when it reached a state worth telling a screen reader. */
export interface News {
  id: number;
  line: Line;
}

/**
 * The checks of the server, made before sending: a Word .doc, a format not accepted, an empty
 * file, a file over the size chosen by the administrators. The server checks again.
 */
export function check(file: File, limits: ImportLimits | undefined): Problem | null {
  const extension = extensionOf(file.name);
  if (extension === 'doc') return { code: 'legacy_word' };
  if (limits && !limits.extensions.includes(extension)) {
    return { code: 'unsupported_format', params: { formats: limits.extensions.join(', ') } };
  }
  if (file.size === 0) return { code: 'empty_file' };
  if (limits && file.size > limits.maxBytes) return { code: 'file_too_large', params: { max: limits.maxMegabytes } };
  return null;
}

/**
 * A file that closing the window would stop: waiting for its turn, or not entirely sent. A file
 * that has entirely left is imported by the server even if the window closes.
 */
export function unsent(line: Line): boolean {
  return line.state === 'waiting' || (line.state === 'sending' && line.percent < 100);
}

function fromView(document: Schemas['DocumentView']): Followed {
  const version = document.currentVersion;
  return {
    id: document.id,
    title: document.title,
    mediaType: version.mediaType,
    extractionStatus: version.extractionStatus,
    extractionError: version.extractionError,
    pageCount: version.pageCount,
    sectionCount: version.sectionCount,
  };
}

function fromItem(item: DocumentItem): Followed {
  return {
    id: item.id,
    title: item.title,
    mediaType: item.mediaType,
    extractionStatus: item.extractionStatus,
    extractionError: item.extractionError,
    pageCount: item.pageCount,
    sectionCount: item.sectionCount,
  };
}

function extracting(line: Line): boolean {
  return line.state === 'imported' && line.document?.extractionStatus === 'PENDING';
}

/**
 * The files of an import window: each one checked, then sent, five at a time, then followed every
 * two seconds until its text is extracted. Kept outside React, which reads it through
 * useSyncExternalStore; closing the window stops what is still being sent.
 */
export class ImportQueue {
  private lines: Line[] = [];
  private news: News[] = [];
  private readonly listeners = new Set<() => void>();
  private readonly uploads = new Map<number, Upload>();
  private readonly onChanged: (spaceId: string, changed?: DocumentItem[]) => void;
  private next = 1;
  private nextNews = 1;
  private timer: ReturnType<typeof setTimeout> | undefined;
  private closed = false;
  /** Changes when the window closes: a request still on its way is then ignored. */
  private generation = 0;

  /**
   * @param onChanged called when documents of a space came or went, or with the documents whose
   *                  extraction changed
   */
  constructor(onChanged: (spaceId: string, changed?: DocumentItem[]) => void) {
    this.onChanged = onChanged;
  }

  subscribe = (listener: () => void): (() => void) => {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  };

  snapshot = (): Line[] => this.lines;

  /** The last changes worth telling a screen reader, oldest first. */
  newsSnapshot = (): News[] => this.news;

  /** True while closing the window would stop files: leaving the page then asks first. */
  blocksLeaving(): boolean {
    return !this.closed && this.lines.some(unsent);
  }

  /** Adds dropped files, each with the destination chosen at that moment. */
  add(files: File[], spaceId: string, fields: ImportFields, limits: ImportLimits | undefined): void {
    const added = files.map((file): Line => {
      const problem = check(file, limits);
      return {
        key: this.next++, file, spaceId, fields, state: problem ? 'refused' : 'waiting', percent: 0,
        problem: problem ?? undefined, maxMegabytes: limits?.maxMegabytes,
      };
    });
    this.lines = [...this.lines, ...added];
    for (const line of added) if (line.state === 'refused') this.tell(line);
    this.emit();
    this.pump();
  }

  /** Sends again, from the start, a file whose sending was interrupted. */
  retry(key: number): void {
    const line = this.find(key);
    if (!line || (line.state !== 'interrupted' && line.state !== 'refused')) return;
    this.patch(key, { state: 'waiting', percent: 0, error: undefined });
    this.pump();
  }

  /** Asks for a new attempt at the extraction, after a failure that may pass. */
  async retryExtraction(key: number): Promise<void> {
    const line = this.find(key);
    const document = line?.document;
    if (!line || !document) return;
    this.patch(key, { busy: true, actionError: undefined });
    try {
      const retried = await call(() => api.POST('/api/v1/documents/{id}/extraction/retry', { params: { path: { id: document.id } } }));
      this.patch(key, { busy: false, document: fromView(retried) }, true);
      this.onChanged(line.spaceId);
      this.follow();
    } catch (error) {
      this.patch(key, { busy: false, actionError: error }, true);
    }
  }

  /** Deletes the document just imported, identical to one already in the space. */
  async removeDuplicate(key: number): Promise<void> {
    const line = this.find(key);
    const document = line?.document;
    if (!line || !document) return;
    this.patch(key, { busy: true, actionError: undefined });
    try {
      await call(() => api.DELETE('/api/v1/documents/{id}', { params: { path: { id: document.id } } }));
      this.patch(key, { busy: false, state: 'removed' }, true);
      this.onChanged(line.spaceId);
    } catch (error) {
      this.patch(key, { busy: false, actionError: error }, true);
    }
  }

  /** The window is shown: what waits is sent, what is extracted is followed. */
  open(): void {
    this.closed = false;
    this.pump();
    this.follow();
  }

  /** The window closes: the files being sent are stopped, and nothing is followed any more. */
  close(): void {
    this.closed = true;
    this.generation += 1;
    clearTimeout(this.timer);
    this.timer = undefined;
    for (const upload of this.uploads.values()) upload.abort();
    this.uploads.clear();
    // Sent again if the window opens again: React may mount it twice in development.
    this.lines = this.lines.map((line) => (line.state === 'sending' ? { ...line, state: 'waiting', percent: 0 } : line));
  }

  private find(key: number): Line | undefined {
    return this.lines.find((line) => line.key === key);
  }

  private emit(): void {
    for (const listener of this.listeners) listener();
  }

  /** Keeps the line as it is now, for the screen readers. */
  private tell(line: Line): void {
    this.news = [...this.news, { id: this.nextNews++, line }].slice(-NEWS_KEPT);
  }

  /** Changes a line; told to screen readers when the change is worth it. */
  private patch(key: number, changes: Partial<Line>, worthTelling = false): void {
    this.lines = this.lines.map((line) => (line.key === key ? { ...line, ...changes } : line));
    const line = worthTelling ? this.find(key) : undefined;
    if (line) this.tell(line);
    this.emit();
  }

  /** Starts the waiting files, as long as fewer than five are being sent. */
  private pump(): void {
    if (this.closed) return;
    let sending = this.lines.filter((line) => line.state === 'sending').length;
    for (const line of this.lines) {
      if (sending >= PARALLEL) break;
      if (line.state !== 'waiting') continue;
      sending += 1;
      this.send(line);
    }
  }

  private send(line: Line): void {
    this.patch(line.key, { state: 'sending', percent: 0 });
    const upload = uploadFile(line.spaceId, line.file, line.fields, (fraction) => {
      // 100 only once the whole file has left.
      const percent = Math.min(100, Math.floor(fraction * 100));
      if (this.uploads.get(line.key) === upload && this.find(line.key)?.percent !== percent) this.patch(line.key, { percent });
    });
    this.uploads.set(line.key, upload);
    upload.done.then(
      (document) => this.received(line, upload, document),
      (error: unknown) => this.failed(line, upload, error),
    );
  }

  private received(line: Line, upload: Upload, document: Schemas['DocumentView']): void {
    if (this.uploads.get(line.key) !== upload) return;
    this.uploads.delete(line.key);
    let followed: Followed;
    try {
      followed = fromView(document);
    } catch {
      this.failed(line, upload, new ApiError(0, 'unexpected_response'));
      return;
    }
    this.patch(line.key, {
      state: 'imported',
      percent: 100,
      document: followed,
      duplicateOf: document.duplicateOf ?? null,
      canDelete: document.actions?.delete ?? false,
    }, true);
    this.onChanged(line.spaceId);
    this.pump();
    this.follow();
  }

  private failed(line: Line, upload: Upload, error: unknown): void {
    if (this.uploads.has(line.key) && this.uploads.get(line.key) !== upload) return;
    this.uploads.delete(line.key);
    if (this.closed) return;
    if (error instanceof ApiError && error.status !== 0) this.patch(line.key, { state: 'refused', percent: 0, error }, true);
    else this.patch(line.key, { state: 'interrupted', percent: 0 }, true);
    this.pump();
  }

  /** Asks again in two seconds while a text is being extracted. */
  private follow(): void {
    if (this.closed || this.timer !== undefined || !this.lines.some(extracting)) return;
    const generation = this.generation;
    this.timer = setTimeout(() => void this.poll(generation), timing.follow);
  }

  private async poll(generation: number): Promise<void> {
    const bySpace = new Map<string, string[]>();
    for (const line of this.lines.filter(extracting)) {
      const ids = bySpace.get(line.spaceId) ?? [];
      if (line.document) ids.push(line.document.id);
      bySpace.set(line.spaceId, ids);
    }
    for (const [spaceId, all] of bySpace) {
      for (let start = 0; start < all.length; start += FOLLOWED_PER_REQUEST) {
        const ids = all.slice(start, start + FOLLOWED_PER_REQUEST);
        try {
          const page = await call(() => api.GET('/api/v1/spaces/{spaceId}/documents', {
            params: { path: { spaceId }, query: { ids, size: ids.length } },
          }));
          if (generation !== this.generation) return;
          this.apply(spaceId, ids, page.items);
        } catch (error) {
          if (generation !== this.generation) return;
          // The space, or the person's place in it, is gone: its documents are no longer followed.
          if (error instanceof ApiError && (error.status === 403 || error.status === 404)) this.apply(spaceId, ids, []);
          // Otherwise asked again at the next turn.
        }
      }
    }
    this.timer = undefined;
    this.follow();
  }

  private apply(spaceId: string, ids: string[], items: DocumentItem[]): void {
    const found = new Map(items.map((item) => [item.id, item]));
    const changed: DocumentItem[] = [];
    let gone = false;
    const told: number[] = [];
    this.lines = this.lines.map((line) => {
      const id = line.document?.id;
      if (line.spaceId !== spaceId || line.state !== 'imported' || !id || !ids.includes(id)) return line;
      const item = found.get(id);
      if (!item) {
        gone = true;
        told.push(line.key);
        return { ...line, state: 'gone' };
      }
      if (item.extractionStatus !== line.document?.extractionStatus) {
        changed.push(item);
        told.push(line.key);
      }
      return { ...line, document: fromItem(item) };
    });
    for (const key of told) {
      const line = this.find(key);
      if (line) this.tell(line);
    }
    this.emit();
    if (gone) this.onChanged(spaceId);
    else if (changed.length > 0) this.onChanged(spaceId, changed);
  }
}
