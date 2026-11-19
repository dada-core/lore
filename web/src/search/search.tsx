import { Fragment, type ReactNode } from 'react';
import type { I18n } from '../i18n/i18n';
import type { Schemas } from '../api/client';

export type SearchPage = Schemas['SearchPage'];
export type SearchResult = Schemas['Result'];
export type TitleResult = Schemas['TitleResult'];
export type PassageRef = Schemas['PassageRef'];

/** What a result passes to the reader it opens: the forms found, and the way back to the list. */
export interface FromSearch {
  /** The words found as written in the document ("serveur" for "serveurs"), for the highlights. */
  words: string[];
  /** The address of the results, to come back to them. */
  results: string;
}

/** The state of a link opened from the results, read by the page of the document. */
export function fromSearch(state: unknown): FromSearch | null {
  const value = (state as { fromSearch?: FromSearch } | null)?.fromSearch;
  if (!value || !Array.isArray(value.words) || typeof value.results !== 'string') return null;
  return { words: value.words.filter((word): word is string => typeof word === 'string').slice(0, 20), results: value.results };
}

/** The link of a passage found: the anchor of the passage and the version it belongs to. */
export function passageLink(documentId: string, query: string, version: number, anchor: string | null | undefined): string {
  const params = new URLSearchParams({ from: 'search', q: query });
  if (anchor) params.set('v', String(version));
  return `/documents/${documentId}?${params.toString()}${anchor ? `#${anchor}` : ''}`;
}

/**
 * A text and the places of its words found, as React elements: never as HTML. The places are
 * {@code [start, end[} in UTF-16 units, the units of JavaScript strings.
 */
export function Highlighted({ text, ranges }: { text: string; ranges: number[][] | undefined }) {
  if (!ranges || ranges.length === 0) return <>{text}</>;
  const parts: ReactNode[] = [];
  let at = 0;
  ranges.forEach(([start, end], index) => {
    if (start < at || end <= start || end > text.length) return;
    if (start > at) parts.push(<Fragment key={`t${index}`}>{text.slice(at, start)}</Fragment>);
    parts.push(<mark key={`m${index}`}>{text.slice(start, end)}</mark>);
    at = end;
  });
  if (at < text.length) parts.push(<Fragment key="end">{text.slice(at)}</Fragment>);
  return <>{parts}</>;
}

/** "Section 4.2 Erreurs fréquentes", "Page 112", "Partie 3", "Début du document". */
export function placeLabel(i18n: I18n, passage: { anchor: string; page?: number | null; headings: string[] }): string {
  const { t } = i18n;
  if (passage.page != null) return t('search.place.page', { page: passage.page });
  const part = /^part-(\d+)/.exec(passage.anchor);
  if (part) return t('search.place.part', { part: Number(part[1]) });
  const last = passage.headings[passage.headings.length - 1];
  if (/^h-0(-|$)/.test(passage.anchor) || !last) return t('search.place.top');
  return t('search.place.section', { title: last });
}

// The last searches, kept in this browser for the person signed in, never on the server. Cleared at
// sign-out and when the session expires, so that a shared computer does not show them to the next
// person.
const RECENT = 'lore.searches.';
const KEPT = 8;

export function recentSearches(userId: string): string[] {
  try {
    const raw = localStorage.getItem(RECENT + userId);
    const list = raw ? (JSON.parse(raw) as unknown) : [];
    return Array.isArray(list) ? list.filter((item): item is string => typeof item === 'string').slice(0, KEPT) : [];
  } catch {
    return [];
  }
}

export function rememberSearch(userId: string, query: string): void {
  const text = query.trim();
  if (!text) return;
  try {
    const list = [text, ...recentSearches(userId).filter((item) => item !== text)].slice(0, KEPT);
    localStorage.setItem(RECENT + userId, JSON.stringify(list));
  } catch {
    // The browser keeps nothing: the page does without.
  }
}

export function forgetSearches(userId?: string): void {
  try {
    if (userId) {
      localStorage.removeItem(RECENT + userId);
      return;
    }
    for (const key of Object.keys(localStorage)) {
      if (key.startsWith(RECENT)) localStorage.removeItem(key);
    }
  } catch {
    // Nothing kept, nothing to clear.
  }
}

/** The filters of the results page, as its address keeps them. */
export interface SearchFilters {
  spaces: string[];
  categories: string[];
  statuses: string[];
  formats: string[];
  updated: '' | '30d' | '12m';
  includeArchived: boolean;
}

export function filtersOf(params: URLSearchParams): SearchFilters {
  const updated = params.get('updated');
  return {
    spaces: params.getAll('space'),
    categories: params.getAll('category'),
    statuses: params.getAll('status'),
    formats: params.getAll('format'),
    updated: updated === '30d' || updated === '12m' ? updated : '',
    includeArchived: params.get('archived') === 'true',
  };
}

export function hasFilters(filters: SearchFilters): boolean {
  return filters.spaces.length + filters.categories.length + filters.statuses.length + filters.formats.length > 0
    || filters.updated !== '' || filters.includeArchived;
}

/** The address of a search: a new query starts without filters. */
export function searchAddress(query: string, filters?: SearchFilters): string {
  const params = new URLSearchParams({ q: query });
  if (filters) {
    filters.spaces.forEach((value) => params.append('space', value));
    filters.categories.forEach((value) => params.append('category', value));
    filters.statuses.forEach((value) => params.append('status', value));
    filters.formats.forEach((value) => params.append('format', value));
    if (filters.updated) params.set('updated', filters.updated);
    if (filters.includeArchived) params.set('archived', 'true');
  }
  return `/search?${params.toString()}`;
}
