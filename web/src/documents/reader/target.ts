// Where the reader opens a document: the fragment of the address names the place (decision 11 of
// the reader), the query says where the link comes from and which version it was made for.
//
//   /documents/{id}?from=search&q=pg_restore&v=4#h-7-2

/** A place in a document: {@code h-7}, {@code part-3} or {@code page-112}; a chunk adds {@code -2}. */
const ANCHOR = /^(h|part|page)-(\d{1,7})(?:-\d{1,7})?$/;

export interface Target {
  /** The place to open, its chunk removed ({@code h-7-2} gives {@code h-7}); null for the top. */
  place: string | null;
  /** The page of a PDF, from {@code page-112}. */
  page: number | null;
  /** The version the link was made for, when it says so. */
  version: number | null;
  /** The words to highlight in the place, from the search. */
  query: string;
  fromSearch: boolean;
}

export function readTarget(search: string, hash: string): Target {
  const params = new URLSearchParams(search);
  let anchor: string;
  try {
    anchor = decodeURIComponent(hash.replace(/^#/, '')).trim().toLowerCase();
  } catch {
    anchor = '';
  }
  const match = ANCHOR.exec(anchor);
  const version = Number.parseInt(params.get('v') ?? '', 10);
  return {
    place: match ? `${match[1]}-${Number(match[2])}` : null,
    page: match && match[1] === 'page' ? Number(match[2]) : null,
    version: Number.isFinite(version) && version > 0 ? version : null,
    query: (params.get('q') ?? '').slice(0, 500),
    fromSearch: params.get('from') === 'search',
  };
}

/**
 * The words of a query, and its quoted phrases: {@code pg_restore role "does not exist"} gives
 * pg_restore, role and "does not exist". Very short words would light up everywhere: left out.
 */
export function termsOf(query: string): string[] {
  const terms: string[] = [];
  const phrase = /"([^"]+)"/g;
  for (const match of query.matchAll(phrase)) terms.push(match[1].trim());
  for (const word of query.replace(phrase, ' ').split(/\s+/)) terms.push(word.trim());
  return [...new Set(terms.filter((term) => term.length >= 2))];
}

/** The link of a document: valid whatever its title, folder or space becomes. */
export function documentLink(id: string): string {
  return `${window.location.origin}/documents/${id}`;
}

/** The link of a section: the version it belongs to, so that it never leads elsewhere in silence. */
export function sectionLink(id: string, version: number, place: string): string {
  return `${documentLink(id)}?v=${version}#${place}`;
}
