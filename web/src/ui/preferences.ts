import { useSyncExternalStore } from 'react';

// Preferences kept in this browser: the theme and the folded panels. Storage may be unavailable
// (private browsing, blocked site data): the defaults then apply, without error.

export type Theme = 'system' | 'light' | 'dark';
export const THEMES: readonly Theme[] = ['system', 'light', 'dark'];
export type Panel = 'nav' | 'settings';

const THEME_KEY = 'lore.theme';
const PANELS_KEY = 'lore.panels';
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
    if (event.key === THEME_KEY || event.key === PANELS_KEY) {
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

/** Whether a side panel is shown, and the function that shows or hides it. */
export function usePanel(panel: Panel): [boolean, () => void] {
  const hidden = useSyncExternalStore(subscribe, hiddenPanels, () => NONE_HIDDEN);
  const toggle = () => {
    write(PANELS_KEY, JSON.stringify({ ...hiddenPanels(), [panel]: !hiddenPanels()[panel] }));
    changed();
  };
  return [!hidden[panel], toggle];
}
