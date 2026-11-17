import type { RefObject } from 'react';
import { useI18n } from '../../i18n/i18n';

/** A title of the document: {@code key} is its anchor ({@code h-7}), or a bookmark of a PDF. */
export interface OutlineItem {
  key: string;
  title: string;
  level: number;
}

/** What the outline and the links of a document ask of the reader that shows it. */
export interface Navigation {
  /** Shows a place, its words of {@code query} marked when it comes from the search. */
  go(key: string, query?: string): void;
}

/** The titles shown: three levels under the highest of the document. */
const LEVELS = 3;

/**
 * "On this page": the titles of the document, the section being read marked as the reading goes.
 * A click leads to the title and replaces the fragment of the address, without a step in the
 * history of the browser (decision 14 of the reader).
 */
export function Outline({ items, current, navigationRef, linkable }: {
  items: OutlineItem[];
  current: string | null;
  navigationRef: RefObject<Navigation | null>;
  /** True when the keys are anchors of the address; a bookmark of a PDF is not. */
  linkable: boolean;
}) {
  const { t } = useI18n();
  if (items.length === 0) return null;
  const top = Math.min(...items.map((item) => item.level));
  const shown = new Set(items.filter((item) => item.level - top < LEVELS).map((item) => item.key));
  // A deeper title being read marks the title shown above it.
  let marked: string | null = null;
  if (current) {
    const index = items.findIndex((item) => item.key === current);
    for (let i = index; i >= 0; i -= 1) {
      if (shown.has(items[i].key)) {
        marked = items[i].key;
        break;
      }
    }
  }
  return (
    <nav className="outline" aria-labelledby="document-outline">
      <h2 id="document-outline">{t('reader.onThisPage')}</h2>
      <ul className="toc">
        {items.filter((item) => shown.has(item.key)).map((item) => (
          <li key={item.key} className={`toc-l${Math.min(item.level - top, LEVELS - 1)}`}>
            <a
              href={linkable ? `#${item.key}` : '#'}
              aria-current={item.key === marked ? 'location' : undefined}
              onClick={(event) => {
                // With a modifier, the browser opens the section in a new tab.
                if (linkable && (event.ctrlKey || event.metaKey || event.shiftKey || event.altKey)) return;
                event.preventDefault();
                navigationRef.current?.go(item.key);
              }}
            >
              {item.title}
            </a>
          </li>
        ))}
      </ul>
    </nav>
  );
}
