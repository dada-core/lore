import { mkdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import AxeBuilder from '@axe-core/playwright';
import {
  expect,
  request,
  test as base,
  type APIRequestContext,
  type BrowserContextOptions,
  type Locator,
  type Page,
} from '@playwright/test';
import { messages, type MessageKey } from '../src/i18n/messages';

export { expect };

/** The interface text in French, as the journeys read it: the organization is set up in French. */
export function t(key: MessageKey, params?: Record<string, string | number>): string {
  const text = messages.fr[key];
  if (!params) return text;
  return text.replace(/\{(\w+)\}/g, (match, name: string) => {
    const value = params[name];
    if (value === undefined) return match;
    return typeof value === 'number' ? new Intl.NumberFormat('fr').format(value) : value;
  });
}

// ---- The people of DadaGroup ----

export type PersonKey = 'wilfried' | 'amenan' | 'herve' | 'eli' | 'christ' | 'elfried';

export interface Person {
  name: string;
  email: string;
  role: 'OWNER' | 'ADMIN' | 'MEMBER';
}

/** Wilfried Kouakou sets Lore up (journey 1); the others are invited by the API (people.setup.ts). */
export const PEOPLE: Record<PersonKey, Person> = {
  wilfried: { name: 'Wilfried Kouakou', email: 'wilfried.kouakou@dada.group', role: 'OWNER' },
  amenan: { name: 'Amenan Souaga', email: 'amenan.souaga@dada.group', role: 'ADMIN' },
  herve: { name: 'Hervé Coulibaly', email: 'herve.coulibaly@dada.group', role: 'MEMBER' },
  eli: { name: 'Eli Brou', email: 'eli.brou@dada.group', role: 'MEMBER' },
  christ: { name: 'Christ Koffi', email: 'christ.koffi@dada.group', role: 'MEMBER' },
  elfried: { name: 'Elfried Yaba', email: 'elfried.yaba@dada.group', role: 'MEMBER' },
};

/** The password of every person of the journeys: a test installation only. */
export const PASSWORD = 'correct-horse-battery-staple';

const AUTH = join(dirname(fileURLToPath(import.meta.url)), '.auth');

/** Where the session of a person is kept between the setup and the journeys. */
export function sessionFile(key: PersonKey): string {
  mkdirSync(AUTH, { recursive: true });
  return join(AUTH, `${key}.json`);
}

/**
 * A journey that changes a person (their role, their password, their sessions) works on a person
 * of its own, so that the journeys running beside it keep theirs: the same name, another address.
 */
export function ownPerson(key: PersonKey, tag: string): Person {
  const person = PEOPLE[key];
  return { ...person, email: person.email.replace('@', `+${tag}@`), role: 'MEMBER' };
}

// ---- The API, to prepare what a journey does not test on screen ----

export interface DocumentInfo {
  id: string;
  title: string;
  status: string;
  currentVersion: { number: number; extractionStatus: string };
}

export class Api {
  private constructor(private readonly context: APIRequestContext) {}

  /** A client with the session saved for that person, or a signed-out one. */
  static async open(baseURL: string, session?: string): Promise<Api> {
    const context = await request.newContext({ baseURL, storageState: session });
    const api = new Api(context);
    await api.get('/api/v1/session');
    return api;
  }

  static async signIn(baseURL: string, person: Person, password = PASSWORD): Promise<Api> {
    const api = await Api.open(baseURL);
    await api.post('/api/v1/auth/login', { email: person.email, password });
    // The CSRF token changes with the session.
    await api.get('/api/v1/session');
    return api;
  }

  private async token(): Promise<string> {
    const state = await this.context.storageState();
    // Named __Host-XSRF-TOKEN when Lore has a public address in https.
    return state.cookies.find((cookie) => cookie.name === 'XSRF-TOKEN' || cookie.name === '__Host-XSRF-TOKEN')?.value ?? '';
  }

  async send<T>(method: string, path: string, data?: unknown): Promise<T> {
    const response = await this.context.fetch(path, {
      method,
      data: data as Record<string, unknown> | undefined,
      headers: { 'X-XSRF-TOKEN': await this.token() },
    });
    if (!response.ok()) throw new Error(`${method} ${path}: ${response.status()} ${await response.text()}`);
    const text = await response.text();
    return (text ? JSON.parse(text) : undefined) as T;
  }

  get<T>(path: string): Promise<T> {
    return this.send<T>('GET', path);
  }

  post<T>(path: string, data?: unknown): Promise<T> {
    return this.send<T>('POST', path, data);
  }

  put<T>(path: string, data?: unknown): Promise<T> {
    return this.send<T>('PUT', path, data);
  }

  patch<T>(path: string, data?: unknown): Promise<T> {
    return this.send<T>('PATCH', path, data);
  }

  /** The response itself, for a status or a header. */
  raw(path: string) {
    return this.context.get(path);
  }

  async createSpace(name: string, slug?: string): Promise<{ id: string; slug: string; name: string }> {
    return this.post('/api/v1/spaces', { name, slug });
  }

  async addMember(spaceId: string, userId: string, role: 'READER' | 'CONTRIBUTOR' | 'MAINTAINER'): Promise<void> {
    await this.post(`/api/v1/spaces/${spaceId}/members`, { userId, role });
  }

  async userId(email: string): Promise<string> {
    const members = await this.get<{ items: { id: string; email: string }[] }>(`/api/v1/admin/members?q=${encodeURIComponent(email)}`);
    const found = members.items.find((member) => member.email === email);
    if (!found) throw new Error(`no member ${email}`);
    return found.id;
  }

  /** Imports a file and waits until its text is extracted. */
  async importFile(spaceId: string, file: SampleFile, fields: Record<string, string> = {}): Promise<DocumentInfo> {
    const response = await this.context.post(`/api/v1/spaces/${spaceId}/documents`, {
      multipart: { ...fields, file: { name: file.name, mimeType: file.mimeType, buffer: file.buffer } },
      headers: { 'X-XSRF-TOKEN': await this.token() },
    });
    if (!response.ok()) throw new Error(`import ${file.name}: ${response.status()} ${await response.text()}`);
    const document = (await response.json()) as DocumentInfo;
    return this.extracted(document.id);
  }

  /** Imports a version of a document and waits until it is the current one. */
  async newVersion(id: string, file: SampleFile, note = ''): Promise<DocumentInfo> {
    const before = await this.get<DocumentInfo>(`/api/v1/documents/${id}`);
    const response = await this.context.post(`/api/v1/documents/${id}/versions`, {
      multipart: { note, file: { name: file.name, mimeType: file.mimeType, buffer: file.buffer } },
      headers: { 'X-XSRF-TOKEN': await this.token() },
    });
    if (!response.ok()) throw new Error(`version of ${id}: ${response.status()} ${await response.text()}`);
    let document: DocumentInfo | undefined;
    await expect
      .poll(async () => {
        document = await this.get<DocumentInfo>(`/api/v1/documents/${id}`);
        return document.currentVersion.number;
      }, { timeout: 120_000, intervals: [500, 1000] })
      .toBe(before.currentVersion.number + 1);
    return document!;
  }

  /** Waits until the extraction of a document ends, then returns it. */
  async extracted(id: string): Promise<DocumentInfo> {
    let document: DocumentInfo | undefined;
    await expect
      .poll(async () => {
        document = await this.get<DocumentInfo>(`/api/v1/documents/${id}`);
        return ['INDEXED', 'SCANNED', 'FAILED'].includes(document.currentVersion.extractionStatus);
      }, { timeout: 120_000, intervals: [500, 1000] })
      .toBe(true);
    return document!;
  }

  async save(path: string): Promise<void> {
    await this.context.storageState({ path });
  }

  async dispose(): Promise<void> {
    await this.context.dispose();
  }
}

/** An invitation made by an administrator, accepted by the person: their session is returned. */
export async function invite(baseURL: string, admin: Api, person: Person): Promise<Api> {
  const { url } = await admin.post<{ url: string }>('/api/v1/admin/invitations', { email: person.email, role: person.role });
  const guest = await Api.open(baseURL);
  await guest.post('/api/v1/invitations/accept', {
    token: new URL(url).hash.slice(1),
    displayName: person.name,
    email: person.email,
    password: PASSWORD,
  });
  await guest.get('/api/v1/session');
  return guest;
}

export interface SampleFile {
  name: string;
  mimeType: string;
  buffer: Buffer;
}

// ---- Guards: what the earlier slices checked by hand, on every page of every journey ----

const AXE_TAGS = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa'];

/** A violation accepted on one element, with its reason. Never a whole screen. */
export interface AxeException {
  rule: string;
  target: string;
  reason: string;
}

export interface Guard {
  /** A server error the journey provokes on purpose. */
  allowServerError(url: RegExp): void;
  /** Starts watching a page opened outside the fixtures. */
  watch(page: Page): void;
}

type Fixtures = {
  guard: Guard;
  /** The axe audit of the screen shown: light, dark, then at 375 px wide. */
  audit: (page: Page, screen: string, exceptions?: AxeException[]) => Promise<void>;
  /** A new page in the session of a person, in a context of its own, a phone for example. */
  as: (person: PersonKey, options?: BrowserContextOptions) => Promise<Page>;
  /** The API in the session of a person. */
  api: (person: PersonKey) => Promise<Api>;
};

const CONTEXT_KEYS = ['baseURL', 'locale', 'timezoneId', 'viewport', 'userAgent', 'deviceScaleFactor', 'isMobile', 'hasTouch'] as const;

export const test = base.extend<Fixtures>({
  guard: async ({ browserName }, use) => {
    const problems: string[] = [];
    const allowed: RegExp[] = [];
    const watch = (page: Page) => {
      page.on('console', (message) => {
        if (message.type() !== 'error') return;
        const text = message.text();
        // The status of a refused request is checked by the journey; a server error, below.
        if (/Failed to load resource: the server responded with a status of 4\d\d/.test(text)) return;
        // Firefox reports a font whose download a new page interrupted (NS_BINDING_ABORTED).
        if (/downloadable font: download failed .*status=2152398850/.test(text)) return;
        problems.push(`console (${browserName}): ${text}`);
      });
      page.on('pageerror', (error) => problems.push(`page error (${browserName}): ${error.message}`));
      page.on('response', (response) => {
        if (response.status() >= 500 && !allowed.some((pattern) => pattern.test(response.url()))) {
          problems.push(`server error: ${response.status()} ${response.request().method()} ${response.url()}`);
        }
      });
    };
    await use({ allowServerError: (url) => allowed.push(url), watch });
    expect(problems, 'console errors, policy violations and server errors').toEqual([]);
  },

  context: async ({ context }, use) => {
    await context.addInitScript(reportPolicyViolations);
    await context.addInitScript(recordClipboard);
    await use(context);
  },

  page: async ({ page, guard }, use) => {
    guard.watch(page);
    await use(page);
  },

  as: async ({ browser, guard }, use, testInfo) => {
    const contexts: Awaited<ReturnType<typeof browser.newContext>>[] = [];
    const options: BrowserContextOptions = {};
    for (const key of CONTEXT_KEYS) {
      const value = testInfo.project.use[key];
      if (value !== undefined) Object.assign(options, { [key]: value });
    }
    await use(async (person, extra = {}) => {
      const context = await browser.newContext({ ...options, ...extra, storageState: sessionFile(person) });
      await context.addInitScript(reportPolicyViolations);
      await context.addInitScript(recordClipboard);
      contexts.push(context);
      const page = await context.newPage();
      guard.watch(page);
      return page;
    });
    for (const context of contexts) await context.close();
  },

  api: async ({ baseURL }, use) => {
    const clients: Api[] = [];
    await use(async (person) => {
      const client = await Api.open(baseURL!, sessionFile(person));
      clients.push(client);
      return client;
    });
    for (const client of clients) await client.dispose();
  },

  audit: async ({}, use) => {
    await use(async (page, screen, exceptions = []) => {
      if (process.env.LORE_E2E_AXE === '0') return;
      await base.step(`axe: ${screen}`, async () => {
        const found: string[] = [];
        const run = async (variant: string) => {
          const results = await new AxeBuilder({ page }).withTags(AXE_TAGS).analyze();
          for (const violation of results.violations) {
            for (const node of violation.nodes) {
              const target = node.target.join(' ');
              if (exceptions.some((exception) => exception.rule === violation.id && exception.target === target)) continue;
              found.push(`${screen} (${variant}) ${violation.id} [${violation.impact}] ${target} : ${violation.help}`);
            }
          }
        };
        // Without transitions, so that no colour is measured halfway.
        await page.emulateMedia({ colorScheme: 'light', reducedMotion: 'reduce' });
        await run('light');
        await page.emulateMedia({ colorScheme: 'dark', reducedMotion: 'reduce' });
        await run('dark');
        await page.emulateMedia({ colorScheme: 'light', reducedMotion: 'reduce' });
        const size = page.viewportSize();
        if (size && size.width > 375) await page.setViewportSize({ width: 375, height: 812 });
        await run('375 px');
        const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
        if (overflow > 0) found.push(`${screen} (375 px) the page scrolls sideways by ${overflow} px`);
        if (size && size.width > 375) await page.setViewportSize(size);
        await page.emulateMedia({ colorScheme: null, reducedMotion: null });
        expect.soft(found, `axe on ${screen}`).toEqual([]);
      });
    });
  },
});

/** Sent to the console, where the guard reads it: a violation of the content security policy. */
function reportPolicyViolations() {
  document.addEventListener('securitypolicyviolation', (event) => {
    console.error(`Content security policy: ${event.violatedDirective} blocked ${event.blockedURI || 'inline code'}`);
  });
}

/**
 * Keeps the last text the page sends to the clipboard, which no engine lets a test read the same
 * way: the page still writes it, or shows it to copy by hand when the browser refuses.
 */
function recordClipboard() {
  const clipboard = navigator.clipboard;
  if (!clipboard) return;
  const write = clipboard.writeText.bind(clipboard);
  clipboard.writeText = (text: string) => {
    (window as unknown as { loreCopied?: string }).loreCopied = text;
    return write(text);
  };
}

/** The last text the page copied, or an empty text. */
export function copied(page: Page): Promise<string> {
  return page.evaluate(() => (window as unknown as { loreCopied?: string }).loreCopied ?? '');
}

/**
 * In a filter of the search, every value starts checked: keeps the values that match and unchecks
 * the others, one at a time, the menu opened and closed again for each one.
 */
export async function keepOnly(page: Page, trigger: Locator, menuName: string, keep: RegExp): Promise<void> {
  const menu = page.getByRole('menu', { name: menuName });
  for (let round = 0; round < 10; round++) {
    await trigger.click();
    await expect(menu).toBeVisible();
    let next: Locator | null = null;
    for (const item of await menu.getByRole('menuitemcheckbox').all()) {
      const wanted = keep.test((await item.textContent()) ?? '');
      if (wanted !== ((await item.getAttribute('aria-checked')) === 'true')) {
        next = item;
        break;
      }
    }
    if (next) await next.click();
    if (await menu.isVisible()) await page.keyboard.press('Escape');
    await expect(menu).toBeHidden();
    if (!next) return;
  }
  throw new Error(`the filter ${menuName} never kept only ${keep}`);
}

// ---- Keyboard ----

/** The element that has the focus shows it: an outline or a ring. */
export async function expectVisibleFocus(page: Page): Promise<void> {
  const shown = await page.evaluate(() => {
    const element = document.activeElement;
    if (!element || element === document.body) return 'nothing has the focus';
    const style = getComputedStyle(element);
    const outline = style.outlineStyle !== 'none' && parseFloat(style.outlineWidth) > 0;
    const ring = style.boxShadow !== 'none';
    return outline || ring ? 'visible' : `no visible focus on ${element.outerHTML.slice(0, 160)}`;
  });
  expect(shown).toBe('visible');
}
