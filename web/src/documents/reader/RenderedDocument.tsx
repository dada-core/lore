import { useQuery } from '@tanstack/react-query';
import { useEffect, useLayoutEffect, useRef, useState, type RefObject } from 'react';
import { api, ApiError, call, type Schemas } from '../../api/client';
import { keys } from '../../api/queryClient';
import { errorMessage } from '../../i18n/errors';
import { useI18n } from '../../i18n/i18n';
import { copyText } from '../../ui/bits';
import { Callout } from '../../ui/forms';
import { Icon, Spinner } from '../../ui/Icon';
import { useNotify } from '../../ui/Toast';
import type { Document } from '../documents';
import { clearHighlights, highlight } from './highlight';
import type { Navigation, OutlineItem } from './Outline';
import { termsOf, type Target } from './target';

type Rendering = Schemas['RenderingView'];

const HEADINGS = 'h1, h2, h3, h4, h5, h6';
/** The places of a rendering: nothing else is looked for in the page. */
const PLACE = /^(h|part)-\d{1,7}$/;
/** A section is read once its top passes this line, below the bar of the page. */
const READING_LINE = 120;

/**
 * The content of a Markdown, Word or text document: the rendering produced at the extraction and
 * cleaned by the server, inserted as it is (decision 6 of the reader), then given what the
 * interface adds around it without touching it: a "Copy" button on each code block, a link on each
 * title, the frame and the marks of the passage opened, the section being read for the outline.
 *
 * A long document comes part by part (about 500,000 characters each): the outline covers the whole
 * document and opens the right part.
 */
export function RenderedDocument({ document, target, navigationRef, onOutline, onReading, onCopySection, onNotice, onShown }: {
  document: Document;
  target: Target;
  navigationRef: RefObject<Navigation | null>;
  onOutline: (items: OutlineItem[]) => void;
  onReading: (anchor: string | null) => void;
  onCopySection: (place: string) => void;
  onNotice: (notice: 'missing' | null) => void;
  /** A place shown by the outline or a link: the address follows it. */
  onShown: (place: string) => void;
}) {
  const i18n = useI18n();
  const { t } = i18n;
  const notify = useNotify();
  const version = document.currentVersion;
  const container = useRef<HTMLDivElement>(null);
  // The part asked: by a place, which the server finds the part of (the place of the address at
  // first), or by its number.
  const [request, setRequest] = useState<{ part: number | null; anchor: string | null }>({ part: null, anchor: target.place });
  const { part, anchor } = request;
  // The outline of the whole document, sent with the first part only.
  const outline = useRef<Schemas['OutlineEntry'][] | null>(null);
  // The place to show once the part that holds it is there: the one of the address at first.
  const pending = useRef<{ place: string; query: string } | null>(target.place ? { place: target.place, query: target.query } : null);
  // Until then, scrolling is the reader's own, towards a place it shows: not a reading to follow.
  const quietUntil = useRef(0);

  const rendering = useQuery({
    queryKey: [...keys.document(document.id), 'rendering', version.id, part ?? `anchor:${anchor ?? ''}`],
    queryFn: () => call(() => api.GET('/api/v1/documents/{id}/versions/{number}/rendering', {
      params: {
        path: { id: document.id, number: version.number },
        query: part === null ? { anchor: anchor ?? undefined } : { part, outline: outline.current === null },
      },
    })),
    staleTime: Infinity,
    placeholderData: (previous) => previous,
  });
  const data: Rendering | undefined = rendering.data;

  useEffect(() => {
    if (data?.outline && outline.current === null) {
      outline.current = data.outline;
      onOutline(data.outline.map((entry) => ({ key: entry.anchor, title: entry.title, level: entry.level })));
    }
  }, [data, onOutline]);

  // The code blocks, the titles and the images get what the interface adds, once per part
  // received (the same part again for a place it does not have: its notice is then shown).
  useLayoutEffect(() => {
    const root = container.current;
    if (!root || !data) return;
    enhance(root, i18n);
    const wanted = pending.current;
    pending.current = null;
    if (wanted) {
      const found = reveal(root, wanted.place, wanted.query);
      onNotice(found ? null : 'missing');
      if (found) {
        quietUntil.current = performance.now() + 800;
        onReading(wanted.place);
      }
    } else if (part !== null) {
      root.scrollIntoView?.({ block: 'start' });
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps -- once per part received
  }, [data]);

  // A printed procedure shows what its <details> hold.
  useEffect(() => {
    const root = container.current;
    if (!root) return undefined;
    let opened: HTMLDetailsElement[] = [];
    const before = () => {
      opened = [...root.querySelectorAll<HTMLDetailsElement>('details:not([open])')];
      opened.forEach((details) => { details.open = true; });
    };
    const after = () => opened.forEach((details) => { details.open = false; });
    window.addEventListener('beforeprint', before);
    window.addEventListener('afterprint', after);
    return () => {
      window.removeEventListener('beforeprint', before);
      window.removeEventListener('afterprint', after);
    };
  }, [data]);

  // The section being read, for the outline.
  useEffect(() => {
    const root = container.current;
    if (!root || !data) return undefined;
    let frame = 0;
    const follow = () => {
      cancelAnimationFrame(frame);
      frame = requestAnimationFrame(() => {
        if (performance.now() >= quietUntil.current) onReading(readingNow(root));
      });
    };
    // Followed once the person scrolls: until then, the place opened stays marked.
    window.addEventListener('scroll', follow, { passive: true });
    return () => {
      cancelAnimationFrame(frame);
      window.removeEventListener('scroll', follow);
    };
  }, [data, onReading]);

  // The outline and the links lead to a place, in this part or in another.
  useEffect(() => {
    navigationRef.current = {
      go(place, query = '') {
        if (!PLACE.test(place)) return;
        const root = container.current;
        onShown(place);
        if (root && root.querySelector(`[id="${place}"]`)) {
          reveal(root, place, query);
          quietUntil.current = performance.now() + 800;
          onReading(place);
          onNotice(null);
          return;
        }
        // In another part: the one the outline names, or the one the server finds.
        pending.current = { place, query };
        const holder = outline.current?.find((entry) => entry.anchor === place);
        setRequest(holder ? { part: holder.part, anchor: null } : { part: null, anchor: place });
      },
    };
    return () => {
      navigationRef.current = null;
    };
  }, [navigationRef, onNotice, onReading, onShown]);

  if (rendering.isPending) {
    return <div className="grid-empty" role="status"><Spinner /><span className="sr-only">{t('reader.loading')}</span></div>;
  }
  if (rendering.isError) {
    const none = rendering.error instanceof ApiError && rendering.error.status === 409;
    return none
      ? <p className="muted">{t('reader.noRendering')}</p>
      : <Callout tone="error" role="alert">{errorMessage(i18n, rendering.error)}</Callout>;
  }
  if (!data) return null;

  // The bar of the parts, at the top (a landmark, announced) and again at the bottom.
  const parts = (top: boolean) => data.partCount > 1 && (
    <div className="parts-bar" role={top ? 'navigation' : undefined} aria-label={top ? t('reader.parts') : undefined}>
      <button type="button" className="btn" disabled={data.part <= 1} onClick={() => setRequest({ part: data.part - 1, anchor: null })}>
        <Icon name="chevron-left" />{t('reader.previousPart')}
      </button>
      <span aria-live={top ? 'polite' : undefined}>{t('reader.partOf', { part: data.part, count: data.partCount })}</span>
      <button type="button" className="btn" disabled={data.part >= data.partCount} onClick={() => setRequest({ part: data.part + 1, anchor: null })}>
        {t('reader.nextPart')}<Icon name="chevron-right" />
      </button>
    </div>
  );

  return (
    <>
      {data.partCount > 1 && <Callout>{t('reader.longDocument')}</Callout>}
      {/* The rendering stops before the text does: a document made of tiny cells, for example. */}
      {data.truncated && !version.textTruncated && <Callout tone="warn">{t('reader.truncatedRest')}</Callout>}
      {parts(true)}
      <div
        ref={container}
        className="prose doc-content"
        lang={version.language === 'fr' || version.language === 'en' ? version.language : undefined}
        // Cleaned twice by the server: a closed list of elements, no script, no style, no event.
        dangerouslySetInnerHTML={{ __html: data.html }}
        onClick={(event) => {
          const element = event.target as Element;
          const copy = element.closest('button[data-copy-code]');
          if (copy) {
            const code = copy.parentElement?.querySelector('pre')?.textContent ?? '';
            void copyText(code).then((copied) => notify(t(copied ? 'reader.codeCopied' : 'common.copyFailed')));
            return;
          }
          const section = element.closest('button[data-copy-section]');
          if (section) {
            onCopySection(section.getAttribute('data-copy-section') ?? '');
            return;
          }
          const link = element.closest('a[href^="#"]');
          // A click with a modifier opens the section in a new tab, as the browser does.
          if (link && !event.ctrlKey && !event.metaKey && !event.shiftKey && !event.altKey) {
            event.preventDefault();
            navigationRef.current?.go((link.getAttribute('href') ?? '').slice(1));
          }
        }}
      />
      {parts(false)}
    </>
  );
}

/** What the interface adds to the rendering, without changing what the server sent. */
function enhance(root: HTMLElement, i18n: ReturnType<typeof useI18n>): void {
  const { t } = i18n;
  for (const pre of root.querySelectorAll('pre')) {
    if (pre.parentElement?.classList.contains('code-block')) continue;
    const block = root.ownerDocument.createElement('div');
    block.className = 'code-block';
    pre.replaceWith(block);
    block.appendChild(pre);
    const copy = root.ownerDocument.createElement('button');
    copy.type = 'button';
    copy.className = 'copy-code';
    copy.setAttribute('data-copy-code', '');
    copy.setAttribute('aria-label', t('reader.copyCode'));
    copy.textContent = t('reader.copy');
    block.appendChild(copy);
  }
  for (const heading of root.querySelectorAll(HEADINGS)) {
    if (heading.parentElement?.classList.contains('heading-row')) continue;
    const place = heading.id || (heading.parentElement?.tagName === 'SECTION' ? heading.parentElement.id : '');
    if (!place) continue;
    const row = root.ownerDocument.createElement('div');
    row.className = 'heading-row';
    heading.replaceWith(row);
    row.appendChild(heading);
    const button = root.ownerDocument.createElement('button');
    button.type = 'button';
    button.className = 'section-link';
    button.setAttribute('data-copy-section', place);
    button.setAttribute('aria-label', t('reader.copySectionLink', { title: heading.textContent ?? '' }));
    button.textContent = '#';
    row.appendChild(button);
  }
  for (const image of root.querySelectorAll('.lore-image')) {
    if (image.querySelector('.image-label')) continue;
    const label = root.ownerDocument.createElement('span');
    label.className = 'image-label';
    label.textContent = t('reader.imageNotShown');
    image.prepend(label);
  }
}

/**
 * Shows a place: scrolled to, framed, its words marked when they come from the search, its title
 * given the focus so that a screen reader starts there. False when the document does not have it.
 */
function reveal(root: HTMLElement, place: string, query: string): boolean {
  for (const framed of root.querySelectorAll('.is-found, .is-pointed')) framed.classList.remove('is-found', 'is-pointed');
  clearHighlights(root);
  const found = root.querySelector<HTMLElement>(`[id="${place}"]`);
  if (!found) return false;
  const element = found.matches(HEADINGS)
    ? found.parentElement?.closest<HTMLElement>('blockquote, li, td, th, details') ?? found
    : found;
  const terms = termsOf(query);
  element.classList.add(terms.length > 0 ? 'is-found' : 'is-pointed');
  const marks = highlight(element, terms);
  (marks[0] ?? element).scrollIntoView?.({ block: 'center' });
  const heading = found.matches(HEADINGS) ? found : found.querySelector<HTMLElement>(HEADINGS);
  if (heading) {
    heading.tabIndex = -1;
    heading.focus({ preventScroll: true });
  }
  if (terms.length === 0) {
    window.setTimeout(() => element.classList.remove('is-pointed'), 2000);
  }
  return true;
}

/** The place being read: the last one whose top went past the reading line. */
function readingNow(root: HTMLElement): string | null {
  const places = [...root.querySelectorAll<HTMLElement>('section[id], h1[id], h2[id], h3[id], h4[id], h5[id], h6[id]')];
  let current: string | null = null;
  let low = 0;
  let high = places.length - 1;
  while (low <= high) {
    const middle = (low + high) >> 1;
    if (places[middle].getBoundingClientRect().top <= READING_LINE) {
      current = places[middle].id;
      low = middle + 1;
    } else {
      high = middle - 1;
    }
  }
  return current;
}
