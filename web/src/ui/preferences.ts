import { useSyncExternalStore } from 'react';

// Preferences kept in this browser: the theme, the folded panels and the view of the library. Storage may be unavailable
// (private browsing, blocked site data): the defaults then apply, without error.

export type Theme = 'system' | 'light' | 'dark';
export const THEMES: readonly Theme[] = ['system', 'light', 'dark'];
export type Panel = 'nav' | 'settings' | 'props';

const THEME_KEY = 'lore.theme';
const PANELS_KEY = 'lore.panels';
const LIBRARY_KEY = 'lore.library';
const listeners = new Set<() => void>();

function read(key: string): string | null {
  try {
    return localStorage.getItem(key);
  } catch {
    return null;
  }
}

function write(key: string, value: string): void {
  try {
    localStorage.setItem(key, value);
  } catch {
    // Not kept beyond this page.
  }
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  const onStorage = (event: StorageEvent) => {
    if (event.key === THEME_KEY || event.key === PANELS_KEY || event.key === LIBRARY_KEY) {
      applyTheme(currentTheme());
      listener();
    }
  };
  window.addEventListener('storage', onStorage);
  return () => {
    listeners.delete(listener);
    window.removeEventListener('storage', onStorage);
  };
}

function changed(): void {
  listeners.forEach((listener) => listener());
}

function currentTheme(): Theme {
  const value = read(THEME_KEY);
  return value === 'light' || value === 'dark' ? value : 'system';
}

function applyTheme(theme: Theme): void {
  if (theme === 'system') document.documentElement.removeAttribute('data-theme');
  else document.documentElement.setAttribute('data-theme', theme);
}

export function setTheme(theme: Theme): void {
  write(THEME_KEY, theme);
  applyTheme(theme);
  changed();
}

export function useTheme(): Theme {
  return useSyncExternalStore(subscribe, currentTheme, () => 'system');
}

export function nextTheme(theme: Theme): Theme {
  return THEMES[(THEMES.indexOf(theme) + 1) % THEMES.length];
}

let panelsCache: { raw: string | null; hidden: Partial<Record<Panel, boolean>> } = { raw: null, hidden: {} };

function hiddenPanels(): Partial<Record<Panel, boolean>> {
  const raw = read(PANELS_KEY);
  if (raw !== panelsCache.raw) panelsCache = { raw, hidden: parsePanels(raw) };
  return panelsCache.hidden;
}

function parsePanels(raw: string | null): Partial<Record<Panel, boolean>> {
  try {
    return raw ? (JSON.parse(raw) as Partial<Record<Panel, boolean>>) : {};
  } catch {
    return {};
  }
}

const NONE_HIDDEN: Partial<Record<Panel, boolean>> = {};

/** The orders of the library, within each folder. */
export const LIBRARY_SORTS = ['updated', 'title', 'category', 'status', 'size'] as const;
export type LibrarySort = (typeof LIBRARY_SORTS)[number];
/** The columns of the library that may be hidden: the title always shows. */
export const LIBRARY_COLUMNS = ['status', 'category', 'owner', 'updated', 'version', 'indexing', 'language', 'format'] as const;
export type LibraryColumn = (typeof LIBRARY_COLUMNS)[number];

export interface LibraryView {
  sort: LibrarySort;
  /** Null: the natural order of the sort, the latest first for the updates. */
  direction: 'asc' | 'desc' | null;
  hidden: LibraryColumn[];
}

const DEFAULT_LIBRARY: LibraryView = { sort: 'updated', direction: null, hidden: [] };
let libraryCache: { raw: string | null; view: LibraryView } = { raw: null, view: DEFAULT_LIBRARY };

function parseLibrary(raw: string | null): LibraryView {
  try {
    const value = raw ? (JSON.parse(raw) as Partial<LibraryView>) : {};
    return {
      sort: LIBRARY_SORTS.includes(value.sort as LibrarySort) ? (value.sort as LibrarySort) : 'updated',
      direction: value.direction === 'asc' || value.direction === 'desc' ? value.direction : null,
      hidden: Array.isArray(value.hidden) ? value.hidden.filter((column): column is LibraryColumn => LIBRARY_COLUMNS.includes(column)) : [],
    };
  } catch {
    return DEFAULT_LIBRARY;
  }
}

/** The view chosen, when the browser keeps nothing: it lasts as long as the page. */
let libraryInMemory: string | null = null;

/** What the browser keeps, or, when it refuses to keep anything, the view chosen in this page. */
function storedLibrary(): string | null {
  try {
    return localStorage.getItem(LIBRARY_KEY);
  } catch {
    return libraryInMemory;
  }
}

function libraryView(): LibraryView {
  const raw = storedLibrary();
  if (raw !== libraryCache.raw) libraryCache = { raw, view: parseLibrary(raw) };
  return libraryCache.view;
}

/** The order and the hidden columns of the library, kept in this browser for every space. */
export function useLibraryView(): [LibraryView, (change: Partial<LibraryView>) => void] {
  const view = useSyncExternalStore(subscribe, libraryView, () => DEFAULT_LIBRARY);
  const change = (next: Partial<LibraryView>) => {
    libraryInMemory = JSON.stringify({ ...libraryView(), ...next });
    write(LIBRARY_KEY, libraryInMemory);
    changed();
  };
  return [view, change];
}

/** Whether a side panel is shown, and the function that shows or hides it. */
export function usePanel(panel: Panel): [boolean, () => void] {
  const hidden = useSyncExternalStore(subscribe, hiddenPanels, () => NONE_HIDDEN);
  const toggle = () => {
    write(PANELS_KEY, JSON.stringify({ ...hiddenPanels(), [panel]: !hiddenPanels()[panel] }));
    changed();
  };
  return [!hidden[panel], toggle];
}
