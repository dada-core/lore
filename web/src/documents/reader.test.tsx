import { QueryClientProvider } from '@tanstack/react-query';
import { act, cleanup, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter } from 'react-router';
import { RouterProvider } from 'react-router/dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { queryClient } from '../api/queryClient';
import { routes } from '../router';
import { eli, herve, item, MARKDOWN, member, page, PDF, space, SPACE_ID, view } from '../test/documents';
import { fakeServer, json } from '../test/server';
import { ToastProvider } from '../ui/Toast';

// pdf.js, replaced by a viewer that only keeps track of what it is asked.
const pdf = vi.hoisted(() => ({
  options: null as Record<string, unknown> | null,
  bus: null as { dispatch(name: string, data: unknown): void } | null,
  destinations: [] as unknown[],
  pages: [] as { div: HTMLDivElement; textLayer: { div: HTMLDivElement } }[],
}));
vi.mock('./reader/pdf', () => {
  class EventBus {
    private handlers = new Map<string, ((data: unknown) => void)[]>();
    on(name: string, handler: (data: unknown) => void) {
      this.handlers.set(name, [...(this.handlers.get(name) ?? []), handler]);
    }
    dispatch(name: string, data: unknown) {
      (this.handlers.get(name) ?? []).forEach((handler) => handler(data));
    }
  }
  class PDFLinkService {
    setViewer() {}
    setDocument() {}
    goToDestination(dest: unknown) {
      pdf.destinations.push(dest);
      return Promise.resolve();
    }
  }
  class PDFViewer {
    pagesCount = 0;
    private page = 1;
    private bus: EventBus;
    constructor(options: { eventBus: EventBus }) {
      this.bus = options.eventBus;
      pdf.bus = options.eventBus;
    }
    setDocument(document: { numPages: number }) {
      this.pagesCount = document.numPages;
      this.bus.dispatch('pagesinit', {});
    }
    set currentPageNumber(number: number) {
      this.page = number;
      this.bus.dispatch('pagechanging', { pageNumber: number });
    }
    get currentPageNumber() {
      return this.page;
    }
    set currentScaleValue(_value: string) {
      this.bus.dispatch('scalechanging', { scale: 1 });
    }
    nextPage() {
      this.currentPageNumber = this.page + 1;
      return true;
    }
    previousPage() {
      this.currentPageNumber = this.page - 1;
      return true;
    }
    increaseScale() {
      this.bus.dispatch('scalechanging', { scale: 1.25 });
    }
    decreaseScale() {
      this.bus.dispatch('scalechanging', { scale: 0.8 });
    }
    getPageView(index: number) {
      return pdf.pages[index];
    }
  }
  return {
    ASSETS: '/pdfjs/',
    loadPdf: () => Promise.resolve({
      pdfjs: {
        AnnotationMode: { ENABLE: 1 },
        getDocument: (options: Record<string, unknown>) => {
          pdf.options = options;
          return {
            promise: Promise.resolve({
              numPages: 5,
              getOutline: () => Promise.resolve([{ title: 'Installation', dest: 'd1', items: [{ title: 'Baie', dest: 'd2', items: [] }] }]),
            }),
            destroy: () => Promise.resolve(),
          };
        },
      },
      viewer: { EventBus, PDFLinkService, PDFViewer, LinkTarget: { BLANK: 2 } },
    }),
  };
});

const ID = 'd0000000-0000-7000-8000-000000000001';
const RUNBOOK_HTML = '<section id="h-0"><p>Avant de commencer.</p></section>'
  + '<section id="h-1"><h2>Restaurer une base</h2><pre><code>CREATE ROLE lore;\n    GRANT ALL;</code></pre></section>'
  + '<section id="h-2"><h3>4.2 Erreurs fréquentes</h3><p>Le rôle n’existe pas : relancez <code>pg_restore</code>.</p>'
  + '<p>Voir <a href="#h-1">la restauration</a>, <span class="lore-image">schéma</span>.</p></section>';
const OUTLINE = [
  { anchor: 'h-1', level: 1, title: 'Restaurer une base', part: 1 },
  { anchor: 'h-2', level: 2, title: '4.2 Erreurs fréquentes', part: 1 },
];

type Routes = Parameters<typeof fakeServer>[0];

function server(document: ReturnType<typeof view>, routes: Routes = {}) {
  const asked: string[] = [];
  fakeServer({
    'GET /api/v1/session': () => json({ setupRequired: false, organization: { id: 'o1', name: 'DadaGroup', locale: 'fr', timeZone: 'UTC' }, user: eli }),
    'GET /api/v1/spaces': () => json([space()]),
    'GET /api/v1/spaces/by-slug/infrastructure': () => json(space()),
    [`GET /api/v1/documents/${ID}`]: () => json(document),
    [`GET /api/v1/documents/${ID}/access`]: () => json({ restricted: document.restricted, spaceMembers: 4, people: [] }),
    [`GET /api/v1/documents/${ID}/versions/1/rendering`]: (request) => {
      asked.push(new URL(request.url).search);
      return json({ number: 1, renderer: 1, part: 1, partCount: 1, truncated: false, outline: OUTLINE, html: RUNBOOK_HTML });
    },
    ...routes,
  });
  return asked;
}

function open(path: string) {
  const router = createMemoryRouter(routes, { initialEntries: [path] });
  render(
    <QueryClientProvider client={queryClient}>
      <ToastProvider>
        <RouterProvider router={router} />
      </ToastProvider>
    </QueryClientProvider>,
  );
  return router;
}

const indexed = (overrides: Record<string, unknown> = {}, version: Record<string, unknown> = {}) =>
  view(overrides, { mediaType: MARKDOWN, filename: 'restaurer.md', extractionStatus: 'INDEXED', sectionCount: 3, language: 'fr', ...version });

beforeEach(() => {
  queryClient.clear();
  pdf.options = null;
  pdf.destinations = [];
  pdf.pages = [];
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe('a Markdown, Word or text document', () => {
  it('shows its rendering, with what the interface adds around it', async () => {
    server(indexed());
    open(`/documents/${ID}`);

    // A title is named by its text alone: the button that copies its link sits beside it.
    expect(await screen.findByRole('heading', { level: 2, name: 'Restaurer une base' })).toBeInTheDocument();
    expect(screen.getByText(/GRANT ALL/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Copier le code' })).toBeInTheDocument();
    // French typography: the spaces around the quotes are non-breaking.
    expect(screen.getByRole('button', { name: /^Copier le lien de la section «\s4\.2 Erreurs fréquentes\s»$/ })).toBeInTheDocument();
    expect(screen.getByText(/^Image non affichée\s:$/)).toBeInTheDocument();
    // The outline of the whole document, on the side.
    const outline = screen.getByRole('navigation', { name: 'Sur cette page' });
    expect(within(outline).getAllByRole('link').map((link) => link.textContent)).toEqual(['Restaurer une base', '4.2 Erreurs fréquentes']);
    expect(screen.queryByText(/Lore n’affiche pas encore/)).not.toBeInTheDocument();
  });

  it('opens at the passage found, framed, its words marked, and says so', async () => {
    const asked = server(indexed());
    open(`/documents/${ID}?from=search&q=role%20pg_restore&v=1#h-2-2`);

    const section = await screen.findByRole('heading', { level: 3, name: /4\.2 Erreurs fréquentes/ });
    // The part that holds the place is asked by its anchor, its chunk removed.
    expect(asked[0]).toBe('?anchor=h-2');
    await waitFor(() => expect(section.closest('section')).toHaveClass('is-found'));
    expect([...section.closest('section')!.querySelectorAll('mark')].map((mark) => mark.textContent)).toEqual(['rôle', 'pg_restore']);
    expect(section).toHaveFocus();
    expect(await screen.findByText(/^Passage trouvé pour «\srole pg_restore\s», section 4\.2 Erreurs fréquentes\. Il est surligné ci-dessous\.$/))
      .toBeInTheDocument();
  });

  it('says when a passage is not in the document, or comes from another version', async () => {
    server(indexed());
    open(`/documents/${ID}#h-9`);
    expect(await screen.findByText('Ce passage n’existe pas dans ce document.')).toBeInTheDocument();
    cleanup();
    queryClient.clear();

    server(indexed());
    open(`/documents/${ID}?v=4#h-2`);
    expect(await screen.findByText('Ce passage a changé depuis la version 4.')).toBeInTheDocument();
    expect(screen.queryByText(/Passage trouvé/)).not.toBeInTheDocument();
  });

  it('copies the link of a section, or shows it when the browser refuses the clipboard', async () => {
    server(indexed());
    open(`/documents/${ID}`);
    const browser = userEvent.setup();
    const write = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, 'clipboard', { value: { writeText: write }, configurable: true });

    await browser.click(await screen.findByRole('button', { name: /^Copier le lien de la section «\sRestaurer une base\s»$/ }));
    expect(write).toHaveBeenCalledWith(`${window.location.origin}/documents/${ID}?v=1#h-1`);
    expect(await screen.findByText('Lien de la section copié')).toBeInTheDocument();

    write.mockRejectedValue(new Error('not allowed'));
    await browser.click(screen.getByRole('button', { name: 'Autres actions' }));
    await browser.click(screen.getByRole('menuitem', { name: 'Copier le lien' }));
    const dialog = await screen.findByRole('dialog', { name: 'Lien du document' });
    expect(within(dialog).getByRole('textbox')).toHaveValue(`${window.location.origin}/documents/${ID}`);
  });

  it('leads to a title from the outline and from the links of the document', async () => {
    const asked = server(indexed());
    const router = open(`/documents/${ID}?open=import`);
    const browser = userEvent.setup();

    const outline = await screen.findByRole('navigation', { name: 'Sur cette page' });
    await browser.click(within(outline).getByRole('link', { name: '4.2 Erreurs fréquentes' }));
    expect(screen.getByRole('heading', { level: 3, name: /4\.2 Erreurs fréquentes/ }).closest('section')).toHaveClass('is-pointed');
    // The address follows, without a step in the history; the rest of the address stays.
    expect(router.state.location.hash).toBe('#h-2');
    expect(router.state.location.search).toBe('?open=import');
    expect(router.state.historyAction).toBe('REPLACE');

    await browser.click(screen.getByRole('link', { name: 'la restauration' }));
    expect(screen.getByRole('heading', { level: 2, name: /Restaurer une base/ }).closest('section')).toHaveClass('is-pointed');
    expect(asked).toHaveLength(1);
  });

  it('follows a link to another place of the open document, with its words and its warnings', async () => {
    server(indexed());
    const router = open(`/documents/${ID}#h-1`);
    await screen.findByRole('heading', { level: 2, name: 'Restaurer une base' });

    await act(() => router.navigate(`/documents/${ID}?from=search&q=pg_restore&v=1#h-2`));
    const section = screen.getByRole('heading', { level: 3, name: /4\.2 Erreurs fréquentes/ }).closest('section')!;
    await waitFor(() => expect(section).toHaveClass('is-found'));
    expect([...section.querySelectorAll('mark')].map((mark) => mark.textContent)).toEqual(['pg_restore']);
    expect(screen.getByText(/^Passage trouvé pour «\spg_restore\s», section 4\.2 Erreurs fréquentes/)).toBeInTheDocument();

    await act(() => router.navigate(`/documents/${ID}?v=3#h-1`));
    expect(await screen.findByText('Ce passage a changé depuis la version 3.')).toBeInTheDocument();

    // A place it does not have: the part comes back the same, and the notice is shown.
    await act(() => router.navigate(`/documents/${ID}#h-9`));
    expect(await screen.findByText('Ce passage n’existe pas dans ce document.')).toBeInTheDocument();
  });

  it('marks the passage of a title inside a quote, and quotes a long search briefly', async () => {
    server(indexed(), {
      [`GET /api/v1/documents/${ID}/versions/1/rendering`]: () => json({
        number: 1, renderer: 1, part: 1, partCount: 1, truncated: false, outline: [{ anchor: 'h-1', level: 3, title: 'Rôle absent', part: 1 }],
        html: '<section id="h-0"><blockquote><h4 id="h-1">Rôle absent</h4><p>Créez le rôle lore.</p></blockquote></section>',
      }),
    });
    const words = `role ${'x'.repeat(200)}`;
    open(`/documents/${ID}?from=search&q=${encodeURIComponent(words)}#h-1`);

    const quote = (await screen.findByRole('heading', { level: 4, name: 'Rôle absent' })).closest('blockquote')!;
    await waitFor(() => expect(quote).toHaveClass('is-found'));
    expect([...quote.querySelectorAll('mark')].map((mark) => mark.textContent)).toEqual(['Rôle', 'rôle']);
    expect(screen.getByText(/^Passage trouvé pour «\srole x{75}…\s»/)).toBeInTheDocument();
  });

  it('shows a long document part by part, the outline opening the right part', async () => {
    const asked: string[] = [];
    server(indexed(), {
      [`GET /api/v1/documents/${ID}/versions/1/rendering`]: (request) => {
        const search = new URL(request.url).searchParams;
        asked.push(new URL(request.url).search);
        const part = Number(search.get('part') ?? '1');
        return json({
          number: 1, renderer: 1, part, partCount: 3, truncated: false,
          outline: search.get('outline') === 'false' ? null : [
            { anchor: 'h-1', level: 1, title: 'Début', part: 1 }, { anchor: 'h-9', level: 1, title: 'Annexe', part: 3 },
          ],
          html: part === 3 ? '<section id="h-9"><h2>Annexe</h2><p>Fin.</p></section>' : `<section id="h-${part}"><h2>Partie ${part}</h2></section>`,
        });
      },
    });
    open(`/documents/${ID}`);
    const browser = userEvent.setup();

    expect(await screen.findByText(/Ce document est long/)).toBeInTheDocument();
    expect(screen.getAllByText('Partie 1 sur 3')).toHaveLength(2);
    await browser.click(screen.getAllByRole('button', { name: 'Partie suivante' })[0]);
    expect(await screen.findByRole('heading', { name: /Partie 2/ })).toBeInTheDocument();
    expect(asked[1]).toBe('?part=2&outline=false');

    await browser.click(within(screen.getByRole('navigation', { name: 'Sur cette page' })).getByRole('link', { name: 'Annexe' }));
    expect(await screen.findByText('Fin.')).toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole('heading', { level: 2, name: /Annexe/ }).closest('section')).toHaveClass('is-pointed'));
  });

  it('keeps its place when a window opens over the page, and follows a link to another place', async () => {
    const asked = server(indexed());
    const router = open(`/documents/${ID}#h-1`);
    await screen.findByRole('heading', { level: 2, name: 'Restaurer une base' });

    await act(() => router.navigate(`/documents/${ID}?open=import#h-1`));
    await act(() => router.navigate(`/documents/${ID}#h-2`));

    // The content was asked once: neither the window nor the link reloaded it.
    expect(asked).toEqual(['?anchor=h-1']);
    await waitFor(() => expect(screen.getByRole('heading', { level: 3, name: '4.2 Erreurs fréquentes' }).closest('section'))
      .toHaveClass('is-pointed'));
  });

  it('folds the panel of the properties with ]', async () => {
    server(indexed());
    open(`/documents/${ID}`);
    const browser = userEvent.setup();
    await screen.findByRole('heading', { level: 2, name: /Restaurer une base/ });
    await browser.keyboard(']');
    expect(screen.getByRole('button', { name: 'Afficher les propriétés' })).toHaveAttribute('aria-expanded', 'false');
    await browser.keyboard(']');
    expect(screen.getByRole('button', { name: 'Masquer les propriétés' })).toHaveAttribute('aria-expanded', 'true');
  });

  it('waits for the extraction, and simplifies a Word document', async () => {
    server(indexed({}, { extractionStatus: 'PENDING' }));
    open(`/documents/${ID}`);
    expect(await screen.findByText('Le contenu s’affichera dès la fin de l’extraction.')).toBeInTheDocument();
    cleanup();
    queryClient.clear();

    server(indexed({}, { mediaType: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document', filename: 'plan.docx' }));
    open(`/documents/${ID}`);
    expect(await screen.findByText(/Affichage simplifié du document Word/)).toBeInTheDocument();
  });

  it('says that the file is missing, and offers no download', async () => {
    server(indexed({}, { fileAvailable: false }));
    open(`/documents/${ID}`);
    expect(await screen.findByText(/Le fichier de ce document est introuvable sur le serveur/)).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Télécharger l’original' })).not.toBeInTheDocument();
  });

  it('prints the rendering, and opens a PDF in the browser to print it', async () => {
    server(indexed());
    open(`/documents/${ID}`);
    const browser = userEvent.setup();
    const print = vi.spyOn(window, 'print').mockImplementation(() => {});
    await browser.click(await screen.findByRole('button', { name: 'Autres actions' }));
    await browser.click(screen.getByRole('menuitem', { name: 'Imprimer' }));
    expect(print).toHaveBeenCalled();
    cleanup();
    queryClient.clear();

    server(view({}, { mediaType: PDF, extractionStatus: 'INDEXED', pageCount: 5 }));
    open(`/documents/${ID}`);
    const windowOpen = vi.spyOn(window, 'open').mockImplementation(() => null);
    await browser.click(await screen.findByRole('button', { name: 'Autres actions' }));
    await browser.click(screen.getByRole('menuitem', { name: 'Ouvrir dans le navigateur' }));
    expect(windowOpen).toHaveBeenCalledWith(`/api/v1/documents/${ID}/versions/1/file?disposition=inline`, '_blank', 'noopener');
  });
});

describe('a PDF', () => {
  it('opens at the page of the address, by ranges, the words of the search marked in it', async () => {
    server(view({}, { mediaType: PDF, filename: 'guide.pdf', extractionStatus: 'INDEXED', pageCount: 5 }));
    for (let i = 0; i < 5; i += 1) {
      const div = document.createElement('div');
      const text = document.createElement('div');
      text.innerHTML = '<span>Restaurez avec </span><span>snapshot restore</span>';
      pdf.pages.push({ div, textLayer: { div: text } });
    }
    open(`/documents/${ID}?from=search&q=snapshot%20restore#page-3`);
    const browser = userEvent.setup();

    expect(await screen.findByText('Page 3 sur 5')).toBeInTheDocument();
    expect(pdf.options).toMatchObject({
      url: `/api/v1/documents/${ID}/versions/1/file?disposition=inline`,
      disableAutoFetch: true,
      disableStream: true,
      useWasm: false,
      standardFontDataUrl: '/pdfjs/standard_fonts/',
    });
    expect(screen.getByText(/^Passage trouvé pour «\ssnapshot restore\s», page 3\. Il est surligné ci-dessous\.$/)).toBeInTheDocument();
    act(() => pdf.bus?.dispatch('textlayerrendered', { pageNumber: 3 }));
    expect(pdf.pages[2].div).toHaveClass('is-found');
    expect([...pdf.pages[2].textLayer.div.querySelectorAll('mark')].map((mark) => mark.textContent)).toEqual(['snapshot', 'restore']);

    await browser.click(screen.getByRole('button', { name: 'Page suivante' }));
    expect(screen.getByText('Page 4 sur 5')).toBeInTheDocument();
    const pages = screen.getByRole('region', { name: /^Pages de «\sPlan reprise 2026\s»$/ });
    pages.focus();
    await browser.keyboard('{ArrowLeft}');
    expect(screen.getByText('Page 3 sur 5')).toBeInTheDocument();
    await browser.keyboard('+');
    expect(screen.getByText('125 %')).toBeInTheDocument();
    await browser.type(screen.getByLabelText('Aller à la page'), '5{Enter}');
    expect(screen.getByText('Page 5 sur 5')).toBeInTheDocument();

    // Its bookmarks fill the outline.
    const outline = screen.getByRole('navigation', { name: 'Sur cette page' });
    await browser.click(within(outline).getByRole('link', { name: 'Baie' }));
    expect(pdf.destinations).toEqual(['d2']);
  });

  it('says when the page of the address is beyond the last one', async () => {
    server(view({}, { mediaType: PDF, extractionStatus: 'INDEXED', pageCount: 5 }));
    open(`/documents/${ID}?from=search&q=baie#page-40`);
    expect(await screen.findByText('Ce passage n’existe pas dans ce document.')).toBeInTheDocument();
    expect(screen.getByText('Page 1 sur 5')).toBeInTheDocument();
    expect(screen.queryByText(/Passage trouvé/)).not.toBeInTheDocument();
  });

  it('says that a scanned PDF has no text layer', async () => {
    server(view({}, { mediaType: PDF, extractionStatus: 'SCANNED', pageCount: 5 }));
    open(`/documents/${ID}`);
    expect(await screen.findByText('Image numérisée, sans couche texte')).toBeInTheDocument();
  });
});

describe('the owner of a document', () => {
  it('is handed over to an active contributor, with a warning for a restricted document', async () => {
    const sent: unknown[] = [];
    const ana = { id: '0193b1f2-0000-7000-8000-000000000009', displayName: 'Amenan Souaga', email: 'amenan.souaga@dada.group' };
    server(indexed({ restricted: true }), {
      [`GET /api/v1/spaces/${SPACE_ID}/members`]: () => json([
        member(eli, 'CONTRIBUTOR', true), member(herve, 'MAINTAINER'), { ...member(ana, 'CONTRIBUTOR'), active: false },
        member({ id: 'r1', displayName: 'Elfried Yaba', email: 'elfried.yaba@dada.group' }, 'READER'),
      ]),
      [`PUT /api/v1/documents/${ID}/owner`]: async (request) => {
        sent.push(await request.json());
        return json(indexed({ restricted: true, owner: { id: herve.id, displayName: herve.displayName },
          actions: { edit: false, delete: false, manageAccess: false, retry: false, changeOwner: false } }));
      },
    });
    const router = open(`/documents/${ID}`);
    const browser = userEvent.setup();

    await browser.click(await screen.findByRole('button', { name: 'Eli Brou', description: 'Changer le responsable' }));
    const dialog = await screen.findByRole('dialog', { name: 'Changer le responsable' });
    // Only active members who may write: not the owner, nor a reader, nor a disabled account.
    expect(within(dialog).getAllByRole('radio')).toHaveLength(1);
    await browser.click(within(dialog).getByRole('radio', { name: /Hervé Coulibaly/ }));
    expect(within(dialog).getByText(/Hervé Coulibaly verra ce document\. Vous ne le verrez plus/)).toBeInTheDocument();
    await browser.click(within(dialog).getByRole('button', { name: 'Changer le responsable' }));

    expect(sent).toEqual([{ userId: herve.id }]);
    await waitFor(() => expect(router.state.location.pathname).toBe('/spaces/infrastructure'));
    expect(screen.getByText('Document confié à Hervé Coulibaly')).toBeInTheDocument();
  });
});

describe('around the reader', () => {
  it('keeps the section asked for through the sign-in', async () => {
    fakeServer({
      'GET /api/v1/session': () => json({ setupRequired: false, organization: null, user: null }),
    });
    const router = open(`/documents/${ID}?v=1#h-2`);
    await waitFor(() => expect(router.state.location.pathname).toBe('/login'));
    expect(new URLSearchParams(router.state.location.search).get('next')).toBe(`/documents/${ID}?v=1#h-2`);
  });

  it('shows on the home page the documents to resume', async () => {
    server(indexed(), {
      'GET /api/v1/imports/attention': () => json([]),
      'GET /api/v1/documents/recent': () => json([{
        id: ID, title: 'Restaurer une base PostgreSQL', status: 'PUBLISHED', viewedAt: new Date(Date.now() - 2 * 3600_000).toISOString(),
        space: { id: SPACE_ID, name: 'Infrastructure', slug: 'infrastructure', color: '#13705E' }, folder: { id: 'f1', name: 'Runbooks' },
      }]),
    });
    open('/');
    const resume = await screen.findByRole('region', { name: 'Reprendre' });
    expect(within(resume).getByRole('link', { name: /Restaurer une base PostgreSQL/ })).toHaveAttribute('href', `/documents/${ID}`);
    expect(within(resume).getByText('Infrastructure · Runbooks · Consulté il y a 2 heures')).toBeInTheDocument();
  });

  it('sorts the library even when the browser keeps nothing', async () => {
    server(indexed(), {
      [`GET /api/v1/spaces/${SPACE_ID}/folders`]: () => json([]),
      [`GET /api/v1/spaces/${SPACE_ID}/documents/summary`]: () => json({ total: 1, inProgress: 0, scanned: 0, failed: 0, folders: [] }),
      [`GET /api/v1/spaces/${SPACE_ID}/documents`]: () => json(page([item({ id: ID })])),
    });
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('blocked'); });
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('blocked'); });
    open('/spaces/infrastructure');
    const browser = userEvent.setup();

    await screen.findByRole('table');
    await browser.click(screen.getByRole('button', { name: /^Trier par\s: Mise à jour$/ }));
    await browser.click(screen.getByRole('menuitemradio', { name: 'Taille' }));
    expect(await screen.findByRole('button', { name: /^Trier par\s: Taille$/ })).toBeInTheDocument();
  });

  it('sorts the library and hides its columns, and remembers it', async () => {
    const asked: string[] = [];
    server(indexed(), {
      [`GET /api/v1/spaces/${SPACE_ID}/folders`]: () => json([]),
      [`GET /api/v1/spaces/${SPACE_ID}/documents/summary`]: () => json({ total: 1, inProgress: 0, scanned: 0, failed: 0, folders: [{ folderId: null, count: 1 }] }),
      [`GET /api/v1/spaces/${SPACE_ID}/documents`]: (request) => {
        asked.push(new URL(request.url).search);
        return json(page([item({ id: ID })]));
      },
    });
    localStorage.removeItem('lore.library');
    open('/spaces/infrastructure');
    const browser = userEvent.setup();

    await screen.findByRole('table');
    expect(asked[0]).toContain('sort=updated&direction=desc');
    await browser.click(screen.getByRole('button', { name: /^Trier par\s: Mise à jour$/ }));
    await browser.click(screen.getByRole('menuitemradio', { name: 'Titre' }));
    await waitFor(() => expect(asked.at(-1)).toContain('sort=title&direction=asc'));

    await browser.click(screen.getByRole('button', { name: 'Masquer des colonnes' }));
    await browser.click(screen.getByRole('menuitemradio', { name: 'Langue' }));
    expect(screen.queryByRole('columnheader', { name: 'Langue' })).not.toBeInTheDocument();
    expect(JSON.parse(localStorage.getItem('lore.library') ?? '{}')).toEqual({ sort: 'title', direction: null, hidden: ['language'] });
  });
});
