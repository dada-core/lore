import { useEffect, useRef, useState, type KeyboardEvent, type RefObject } from 'react';
import { useI18n } from '../../i18n/i18n';
import { Callout } from '../../ui/forms';
import { Icon, Spinner } from '../../ui/Icon';
import type { Document } from '../documents';
import { highlight } from './highlight';
import type { Navigation, OutlineItem } from './Outline';
import { ASSETS, loadPdf } from './pdf';
import { termsOf, type Target } from './target';

/** Bookmarks of a PDF shown in the outline: three levels. */
const OUTLINE_DEPTH = 3;
const OUTLINE_MAX = 500;

interface Bookmark {
  title: string;
  dest: string | unknown[] | null;
  items: Bookmark[];
}

/**
 * A PDF in the page, read by pdf.js from the original served by Lore (decision 2 of the reader):
 * only the pages read are asked for, by ranges; the page of the address is shown first, framed, and
 * the words of the search marked in it. Scripts and forms of the PDF stay off; its links open in a
 * new tab; WebAssembly is not used, which the policy of the interface does not allow.
 */
export default function PdfViewer({ document, target, scanned, navigationRef, onOutline, onNotice }: {
  document: Document;
  target: Target;
  scanned: boolean;
  navigationRef: RefObject<Navigation | null>;
  onOutline: (items: OutlineItem[]) => void;
  /** A page of the address that the PDF does not have: it opens at its first page. */
  onNotice: (notice: 'missing' | null) => void;
}) {
  const i18n = useI18n();
  const { t } = i18n;
  const container = useRef<HTMLDivElement>(null);
  const pages = useRef<HTMLDivElement>(null);
  const controls = useRef<{ next(): void; previous(): void; zoomIn(): void; zoomOut(): void; go(page: number): void } | null>(null);
  const [page, setPage] = useState(target.page ?? 1);
  const [count, setCount] = useState<number | null>(null);
  const [scale, setScale] = useState<number | null>(null);
  const [failed, setFailed] = useState(false);
  const [asked, setAsked] = useState('');
  const version = document.currentVersion;

  useEffect(() => {
    const stage = container.current;
    const viewerElement = pages.current;
    if (!stage || !viewerElement) return undefined;
    let cancelled = false;
    let destroy: (() => void) | null = null;
    const found = target.page;
    const terms = scanned ? [] : termsOf(target.query);

    void (async () => {
      try {
        const { pdfjs, viewer } = await loadPdf();
        if (cancelled) return;
        const eventBus = new viewer.EventBus();
        const linkService = new viewer.PDFLinkService({
          eventBus,
          externalLinkTarget: viewer.LinkTarget.BLANK,
          externalLinkRel: 'noopener noreferrer nofollow',
        });
        const pdfViewer = new viewer.PDFViewer({
          container: stage,
          viewer: viewerElement,
          eventBus,
          linkService,
          textLayerMode: 1,
          annotationMode: pdfjs.AnnotationMode.ENABLE,
        });
        linkService.setViewer(pdfViewer);
        eventBus.on('pagesinit', () => {
          pdfViewer.currentScaleValue = stage.clientWidth < 700 ? 'page-width' : 'auto';
          if (found && found >= 1 && found <= pdfViewer.pagesCount) pdfViewer.currentPageNumber = found;
          else if (found) {
            setPage(1);
            onNotice('missing');
          }
        });
        eventBus.on('pagechanging', (event: { pageNumber: number }) => setPage(event.pageNumber));
        eventBus.on('scalechanging', (event: { scale: number }) => setScale(event.scale));
        // The page found is framed, and its words marked each time its text is laid out again.
        eventBus.on('textlayerrendered', (event: { pageNumber: number }) => {
          if (!found || event.pageNumber !== found) return;
          const view = pdfViewer.getPageView(found - 1) as { div: HTMLDivElement; textLayer: { div: HTMLDivElement } | null };
          view.div.classList.add('is-found');
          if (view.textLayer && terms.length > 0) highlight(view.textLayer.div, terms);
        });
        const task = pdfjs.getDocument({
          url: `/api/v1/documents/${document.id}/versions/${version.number}/file?disposition=inline`,
          // Only the pages read are asked for: a guide of 300 pages opens at page 112 at once.
          disableAutoFetch: true,
          disableStream: true,
          useWasm: false,
          wasmUrl: `${ASSETS}wasm/`,
          standardFontDataUrl: `${ASSETS}standard_fonts/`,
          cMapUrl: `${ASSETS}cmaps/`,
          enableXfa: false,
        });
        destroy = () => {
          void task.destroy();
        };
        const pdf = await task.promise;
        if (cancelled) return;
        pdfViewer.setDocument(pdf);
        linkService.setDocument(pdf, null);
        setCount(pdf.numPages);
        controls.current = {
          next: () => pdfViewer.nextPage(),
          previous: () => pdfViewer.previousPage(),
          zoomIn: () => pdfViewer.increaseScale(),
          zoomOut: () => pdfViewer.decreaseScale(),
          go: (number) => {
            pdfViewer.currentPageNumber = Math.min(Math.max(1, number), pdf.numPages);
          },
        };
        const bookmarks = ((await pdf.getOutline()) ?? []) as Bookmark[];
        if (cancelled) return;
        const destinations = new Map<string, Bookmark['dest']>();
        const items: OutlineItem[] = [];
        const walk = (list: Bookmark[], level: number, prefix: string) => {
          list.forEach((bookmark, index) => {
            if (items.length >= OUTLINE_MAX || level > OUTLINE_DEPTH) return;
            const key = `${prefix}${index}`;
            destinations.set(key, bookmark.dest);
            items.push({ key, title: bookmark.title, level });
            walk(bookmark.items ?? [], level + 1, `${key}.`);
          });
        };
        walk(bookmarks, 1, 'pdf-');
        onOutline(items);
        navigationRef.current = {
          go(key) {
            // A bookmark of the outline, or a page of the address (#page-112).
            const page = /^page-(\d{1,7})$/.exec(key);
            if (page) {
              pdfViewer.currentPageNumber = Math.min(Math.max(1, Number(page[1])), pdf.numPages);
              return;
            }
            const dest = destinations.get(key);
            if (dest) void linkService.goToDestination(dest as string);
          },
        };
      } catch {
        if (!cancelled) setFailed(true);
      }
    })();

    return () => {
      cancelled = true;
      navigationRef.current = null;
      controls.current = null;
      destroy?.();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps -- once per version
  }, [document.id, version.number]);

  const keys = (event: KeyboardEvent<HTMLDivElement>) => {
    const typing = (event.target as HTMLElement).closest('input, textarea');
    if (typing || event.ctrlKey || event.metaKey || event.altKey) return;
    const act = { ArrowRight: 'next', ArrowLeft: 'previous', '+': 'zoomIn', '=': 'zoomIn', '-': 'zoomOut' }[event.key] as
      'next' | 'previous' | 'zoomIn' | 'zoomOut' | undefined;
    if (act && controls.current) {
      event.preventDefault();
      controls.current[act]();
    }
  };

  if (failed) {
    return <Callout tone="error" role="alert">{t('reader.pdfFailed')}</Callout>;
  }
  return (
    <div className="pdf">
      <div className="pdf-bar">
        <div className="pager">
          <button type="button" className="icon-btn" aria-label={t('reader.previousPage')} disabled={page <= 1} onClick={() => controls.current?.previous()}>
            <Icon name="chevron-left" />
          </button>
          <span aria-live="polite">{count ? t('reader.pageOf', { page, count }) : t('reader.loading')}</span>
          <button type="button" className="icon-btn" aria-label={t('reader.nextPage')} disabled={!count || page >= count} onClick={() => controls.current?.next()}>
            <Icon name="chevron-right" />
          </button>
          <form
            className="pdf-goto"
            onSubmit={(event) => {
              event.preventDefault();
              const number = Number.parseInt(asked, 10);
              if (Number.isFinite(number)) controls.current?.go(number);
              setAsked('');
            }}
          >
            <label className="sr-only" htmlFor="pdf-goto">{t('reader.goToPage')}</label>
            <input id="pdf-goto" className="input" inputMode="numeric" placeholder={t('reader.goToPage')} value={asked} onChange={(event) => setAsked(event.target.value.replace(/\D/g, ''))} />
          </form>
        </div>
        {scanned && <span>{t('reader.scannedPdf')}</span>}
        <div className="pager">
          <button type="button" className="icon-btn" aria-label={t('reader.zoomOut')} onClick={() => controls.current?.zoomOut()}>−</button>
          <span>{scale ? `${Math.round(scale * 100)} %` : ''}</span>
          <button type="button" className="icon-btn" aria-label={t('reader.zoomIn')} onClick={() => controls.current?.zoomIn()}>+</button>
        </div>
      </div>
      <div className="pdf-stage-frame">
        {!count && <div className="pdf-loading" role="status"><Spinner /><span className="sr-only">{t('reader.loading')}</span></div>}
        {/* pdf.js wants its container positioned absolutely; it scrolls the pages itself. */}
        <div
          ref={container}
          className="pdf-stage"
          tabIndex={0}
          role="region"
          aria-label={t('reader.pdfRegion', { title: document.title })}
          onKeyDown={keys}
        >
          <div ref={pages} className="pdfViewer" />
        </div>
      </div>
    </div>
  );
}
