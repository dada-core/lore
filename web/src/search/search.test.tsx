import { QueryClientProvider } from '@tanstack/react-query';
import { act, cleanup, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter } from 'react-router';
import { RouterProvider } from 'react-router/dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { queryClient } from '../api/queryClient';
import { routes } from '../router';
import { eli, MARKDOWN, space, SPACE_ID, view } from '../test/documents';
import { fakeServer, json } from '../test/server';
import { ToastProvider } from '../ui/Toast';
import { forgetSearches, recentSearches, rememberSearch } from './search';

const ID = '0193b1f2-0000-7000-8000-0000000000d1';
const OTHER = '0193b1f2-0000-7000-8000-0000000000d2';
const FACETS = {
  spaces: [{ id: SPACE_ID, name: 'Infrastructure', slug: 'infrastructure', archived: false, count: 2 }],
  categories: [{ value: 'RUNBOOK', count: 1 }, { value: 'INCIDENT_REPORT', count: 1 }],
  statuses: [{ value: 'PUBLISHED', count: 2 }],
  formats: [{ value: MARKDOWN, count: 2 }],
};

function result(id: string, title: string, overrides: Record<string, unknown> = {}) {
  return {
    document: {
      id, title, titleHighlights: [[0, title.includes(' ') ? title.indexOf(' ') : title.length]], status: 'PUBLISHED', category: 'RUNBOOK', mediaType: MARKDOWN, language: 'fr',
      restricted: false, extractionStatus: 'INDEXED', updatedAt: new Date().toISOString(),
      space: { id: SPACE_ID, name: 'Infrastructure', slug: 'infrastructure', archived: false }, folder: { id: 'f1', name: 'Runbooks' },
    },
    version: 4,
    match: 'passage',
    passage: {
      anchor: 'h-7-2', page: null, headings: ['4. Vérifier', '4.2 Erreurs fréquentes'],
      excerpt: 'Si pg_restore signale role "reporting" does not exist, créez le rôle.', highlights: [[3, 13], [22, 26], [64, 68]],
    },
    description: null,
    words: ['pg_restore', 'role', 'rôle'],
    otherPassages: { total: 2, items: [{ anchor: 'h-3', page: null, headings: ['2. Préparer'] }, { anchor: 'h-0', page: null, headings: [] }] },
    ...overrides,
  };
}

type Routes = Parameters<typeof fakeServer>[0];

function server(routes: Routes = {}) {
  const asked: URL[] = [];
  const fake = fakeServer({
    'GET /api/v1/session': () => json({ setupRequired: false, organization: { id: 'o1', name: 'DadaGroup', locale: 'fr', timeZone: 'UTC' }, user: eli }),
    'GET /api/v1/spaces': () => json([space()]),
    'GET /api/v1/documents/recent': () => json([]),
    'GET /api/v1/search': (request) => {
      const url = new URL(request.url);
      asked.push(url);
      const page = Number(url.searchParams.get('page') ?? 0);
      const items = page === 0
        ? [result(ID, 'pg_restore échoue : rôle inexistant'), result(OTHER, 'Restaurer une base', { otherPassages: { total: 0, items: [] } })]
        : [result('0193b1f2-0000-7000-8000-0000000000d3', 'Plan de reprise', { match: 'title', passage: null, otherPassages: { total: 0, items: [] } })];
      return json({ items, page, size: 2, total: 3, facets: FACETS });
    },
    ...routes,
  });
  return { asked, fake };
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

beforeEach(() => {
  queryClient.clear();
  forgetSearches();
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe('the search page', () => {
  it('shows the results: titles and excerpts marked, the place, the other passages, more results', async () => {
    const { asked } = server();
    const router = open('/search?q=pg_restore%20role');
    const browser = userEvent.setup();

    const first = await screen.findByRole('article', { name: 'pg_restore échoue : rôle inexistant' });
    expect([...first.querySelectorAll('mark')].map((mark) => mark.textContent)).toEqual(['pg_restore', 'pg_restore', 'role', 'rôle']);
    expect(within(first).getByText('Section 4.2 Erreurs fréquentes')).toBeInTheDocument();
    expect(within(first).getByText('Infrastructure › Runbooks')).toBeInTheDocument();
    expect(screen.getByText(/^3 documents$/, { selector: '.results-meta span' })).toBeInTheDocument();
    // Announced once, by a region that stays.
    expect(screen.getByText(/^3 documents$/, { selector: '[aria-live="polite"]' })).toBeInTheDocument();
    expect(within(first).getByRole('link', { name: 'pg_restore échoue : rôle inexistant' }))
      .toHaveAttribute('href', `/documents/${ID}?from=search&q=pg_restore+role&v=4#h-7-2`);

    await browser.click(within(first).getByRole('button', { name: '2 autres passages dans ce document' }));
    expect(within(first).getByRole('link', { name: 'Section 2. Préparer' })).toBeVisible();
    expect(within(first).getByRole('link', { name: 'Début du document' })).toBeVisible();

    await browser.click(screen.getByRole('button', { name: 'Afficher plus de résultats' }));
    const titled = await screen.findByRole('article', { name: 'Plan de reprise' });
    expect(within(titled).getByText('Correspondance dans le titre.')).toBeInTheDocument();
    expect(asked.map((url) => url.searchParams.get('page'))).toEqual(['0', '1']);
    // Kept in this browser for the next time.
    expect(recentSearches(eli.id)).toEqual(['pg_restore role']);
    expect(router.state.location.search).toBe('?q=pg_restore%20role');
  });

  it('narrows the results by a filter, kept in the address', async () => {
    const { asked } = server();
    const router = open('/search?q=sauvegarde');
    const browser = userEvent.setup();

    await screen.findByRole('article', { name: 'Restaurer une base' });
    await browser.click(screen.getByRole('button', { name: /^Catégorie/ }));
    await browser.click(screen.getByRole('menuitemcheckbox', { name: /Rapport d’incident/ }));

    await waitFor(() => expect(router.state.location.search).toBe('?q=sauvegarde&category=RUNBOOK'));
    await waitFor(() => expect(asked.at(-1)?.searchParams.getAll('category')).toEqual(['RUNBOOK']));
    expect(await screen.findByRole('button', { name: /^Catégorie\s: 1 sur 2/ })).toBeInTheDocument();
  });

  it('names the document that replaces an obsolete one, and leaves the archived ones unchecked until asked', async () => {
    const { asked } = server({
      'GET /api/v1/search': (request) => {
        asked.push(new URL(request.url));
        return json({
          items: [result(ID, 'Sauvegardes Barman', {
            document: { ...result(ID, 'Sauvegardes Barman').document, status: 'OBSOLETE', replacedBy: { id: OTHER, title: 'Restaurer une base' } },
          })],
          page: 0, size: 20, total: 1,
          facets: { ...FACETS, statuses: [{ value: 'OBSOLETE', count: 1 }, { value: 'ARCHIVED', count: 2 }] },
        });
      },
    });
    const router = open('/search?q=barman');
    const browser = userEvent.setup();

    const found = await screen.findByRole('article', { name: 'Sauvegardes Barman' });
    expect(within(found).getByRole('link', { name: 'Restaurer une base' })).toHaveAttribute('href', `/documents/${OTHER}`);
    await browser.click(screen.getByRole('button', { name: /^Statut/ }));
    expect(screen.getByRole('menuitemcheckbox', { name: /Archivé/ })).toHaveAttribute('aria-checked', 'false');
    await browser.click(screen.getByRole('menuitemcheckbox', { name: /Archivé/ }));
    await waitFor(() => expect(router.state.location.search).toBe('?q=barman&status=OBSOLETE&status=ARCHIVED'));
  });

  it('says what to change when nothing is found or the search is refused', async () => {
    server({
      'GET /api/v1/search': (request) => (new URL(request.url).searchParams.get('q') === 'le la de'
        ? json({ status: 400, code: 'invalid_request', errors: [{ field: 'q', code: 'common_words_only', params: {} }] }, 400)
        : json({ items: [], page: 0, size: 20, total: 0, facets: { spaces: [], categories: [], statuses: [], formats: [] } })),
    });
    const router = open('/search?q=quota%20ceph');

    expect(await screen.findByRole('heading', { name: /Aucun document ne correspond à «\squota ceph\s»/ })).toBeInTheDocument();
    await act(() => router.navigate('/search?q=le%20la%20de'));
    expect(await screen.findByText(/Précisez votre recherche/)).toBeInTheDocument();
    expect(screen.getByRole('searchbox', { name: 'Rechercher dans vos documents' })).toHaveAttribute('aria-invalid', 'true');
  });

  it('says when the filters leave nothing, and removes them', async () => {
    const { asked } = server({
      'GET /api/v1/search': (request) => {
        const url = new URL(request.url);
        asked.push(url);
        return url.searchParams.has('updated')
          ? json({ items: [], page: 0, size: 20, total: 0, facets: FACETS })
          : json({ items: [result(ID, 'Restaurer une base')], page: 0, size: 20, total: 1, facets: FACETS });
      },
    });
    const router = open('/search?q=sauvegarde&updated=30d');

    expect(await screen.findByText('Aucun résultat avec ces filtres.')).toBeInTheDocument();
    await userEvent.setup().click(screen.getByRole('button', { name: 'Retirer les filtres' }));
    await waitFor(() => expect(router.state.location.search).toBe('?q=sauvegarde'));
    expect(await screen.findByRole('article', { name: 'Restaurer une base' })).toBeInTheDocument();
  });

  it('without a query, offers the recent searches, and forgets them', async () => {
    server();
    rememberSearch(eli.id, 'bascule kubernetes');
    open('/search');
    const browser = userEvent.setup();

    expect(await screen.findByRole('link', { name: 'bascule kubernetes' })).toHaveAttribute('href', '/search?q=bascule+kubernetes');
    expect(screen.getByRole('heading', { name: 'Syntaxe' })).toBeInTheDocument();
    await browser.click(screen.getByRole('button', { name: 'Effacer' }));
    expect(screen.queryByRole('link', { name: 'bascule kubernetes' })).not.toBeInTheDocument();
    expect(recentSearches(eli.id)).toEqual([]);
  });
});

describe('the palette', () => {
  it('opens with Ctrl K, searches the documents first, then names the titles found', async () => {
    server({
      'GET /api/v1/search/titles': () => json([{
        id: ID, title: 'Restaurer une base', titleHighlights: [[0, 7]], status: 'PUBLISHED', mediaType: MARKDOWN,
        space: { id: SPACE_ID, name: 'Infrastructure', slug: 'infrastructure', archived: false }, folder: null,
      }]),
    });
    const router = open('/spaces');
    const browser = userEvent.setup();
    await screen.findByRole('heading', { level: 1 });

    await browser.keyboard('{Control>}k{/Control}');
    const palette = await screen.findByRole('dialog', { name: 'Rechercher ou aller à' });
    const field = within(palette).getByRole('combobox');
    expect(within(palette).getByRole('option', { name: /Accueil/ })).toBeInTheDocument();

    await browser.type(field, 'restau');
    expect(within(palette).getAllByRole('option')[0]).toHaveTextContent(/Rechercher «\srestau\s» dans les documents/);
    expect(await within(palette).findByRole('option', { name: /Restaurer une base/ })).toBeInTheDocument();
    await browser.keyboard('{Enter}');
    await waitFor(() => expect(router.state.location.pathname).toBe('/search'));
    expect(router.state.location.search).toBe('?q=restau');
    expect(screen.queryByRole('dialog', { name: 'Rechercher ou aller à' })).not.toBeInTheDocument();
  });

  it('names each group once, the search first and the titles found last', async () => {
    server({
      'GET /api/v1/search/titles': () => json([{
        id: ID, title: 'Tous les accès VPN', titleHighlights: [[0, 4]], status: 'PUBLISHED', mediaType: MARKDOWN,
        space: { id: SPACE_ID, name: 'Infrastructure', slug: 'infrastructure', archived: false }, folder: null,
      }]),
    });
    open('/spaces');
    const browser = userEvent.setup();
    await screen.findByRole('heading', { level: 1 });

    await browser.keyboard('{Control>}k{/Control}');
    const palette = await screen.findByRole('dialog', { name: 'Rechercher ou aller à' });
    await browser.type(within(palette).getByRole('combobox'), 'tous');
    expect(await within(palette).findByRole('option', { name: /Tous les accès VPN/ })).toBeInTheDocument();
    expect(within(palette).getAllByRole('group').map((group) => group.getAttribute('aria-labelledby'))
      .map((id) => palette.querySelector(`[id="${id}"]`)?.textContent)).toEqual(['Recherche', 'Aller à', 'Documents']);
  });

  it('leaves Ctrl K and / alone while typing, and / opens the search', async () => {
    server();
    const router = open('/spaces');
    const browser = userEvent.setup();
    await screen.findByRole('heading', { level: 1 });

    await browser.keyboard('/');
    await waitFor(() => expect(router.state.location.pathname).toBe('/search'));
    const field = await screen.findByRole('searchbox', { name: 'Rechercher dans vos documents' });
    await waitFor(() => expect(field).toHaveFocus());
    await browser.keyboard('{Control>}k{/Control}/');
    expect(screen.queryByRole('dialog', { name: 'Rechercher ou aller à' })).not.toBeInTheDocument();
    expect(field).toHaveValue('/');
  });
});

describe('the language of a document', () => {
  it('is changed by a contributor, after a confirmation, and the document is indexed again', async () => {
    const document = view({ id: ID }, { mediaType: MARKDOWN, filename: 'r.md', extractionStatus: 'INDEXED', sectionCount: 3, language: 'fr' });
    const { fake } = server({
      [`GET /api/v1/documents/${ID}`]: () => json(document),
      [`PATCH /api/v1/documents/${ID}`]: async (request) => {
        const body = await request.json() as { language: string };
        return json({ ...document, currentVersion: { ...document.currentVersion, language: body.language } });
      },
      [`GET /api/v1/documents/${ID}/access`]: () => json({ restricted: false, spaceMembers: 4, people: [] }),
      'GET /api/v1/spaces/by-slug/infrastructure': () => json(space()),
      [`GET /api/v1/documents/${ID}/versions/1/rendering`]: () => json({
        number: 1, renderer: 1, part: 1, partCount: 1, truncated: false, outline: [], html: '<section id="h-0"><p>Text.</p></section>',
      }),
      'GET /api/v1/spaces/0193b1f2-0000-7000-8000-0000000000a1/documents': () => json({ items: [], page: 0, size: 50, total: 0 }),
    });
    open(`/documents/${ID}`);
    const browser = userEvent.setup();

    await browser.click(await screen.findByRole('button', { name: /^Langue\s: Français$/ }));
    await browser.click(screen.getByRole('menuitemradio', { name: 'Anglais' }));
    const confirm = await screen.findByRole('dialog', { name: /^Changer la langue du document\s\?$/ });
    expect(within(confirm).getByText(/relance l’indexation du document/)).toBeInTheDocument();
    await browser.click(within(confirm).getByRole('button', { name: 'Changer la langue' }));

    expect(await screen.findByText(/^Langue mise à jour\s: le document est réindexé\.$/)).toBeInTheDocument();
    const patch = fake.requests.find((request) => request.method === 'PATCH');
    expect(await patch!.json()).toEqual({ language: 'en' });
    expect(screen.getByRole('button', { name: /^Langue\s: Anglais$/ })).toBeInTheDocument();
  });
});

describe('a document opened from the results', () => {
  it('marks the forms found, and leads back to the results', async () => {
    const document = view({ id: ID }, { mediaType: MARKDOWN, filename: 'r.md', extractionStatus: 'INDEXED', sectionCount: 3, language: 'fr' });
    server({
      'GET /api/v1/search': () => json({
        items: [result(ID, 'pg_restore échoue : rôle inexistant', { version: 1, words: ['relancez'] })],
        page: 0, size: 20, total: 1, facets: FACETS,
      }),
      [`GET /api/v1/documents/${ID}`]: () => json(document),
      [`GET /api/v1/documents/${ID}/access`]: () => json({ restricted: false, spaceMembers: 4, people: [] }),
      'GET /api/v1/spaces/by-slug/infrastructure': () => json(space()),
      [`GET /api/v1/documents/${ID}/versions/1/rendering`]: () => json({
        number: 1, renderer: 1, part: 1, partCount: 1, truncated: false,
        outline: [{ anchor: 'h-7', level: 2, title: '4.2 Erreurs fréquentes', part: 1 }],
        html: '<section id="h-7"><h3>4.2 Erreurs fréquentes</h3><p>Les serveurs or le serveur : relancez, sans barman.</p></section>',
      }),
    });
    const router = open('/search?q=serveurs%20or%20relance%20-barman');
    const browser = userEvent.setup();

    const found = await screen.findByRole('article', { name: 'pg_restore échoue : rôle inexistant' });
    // The words found by the server, passed to the reader: "relancez" for "relance".
    await browser.click(within(found).getByRole('link', { name: 'pg_restore échoue : rôle inexistant' }));
    const section = (await screen.findByRole('heading', { level: 3, name: /4\.2 Erreurs fréquentes/ })).closest('section')!;
    await waitFor(() => expect(section).toHaveClass('is-found'));
    const marked = [...section.querySelectorAll('mark')].map((mark) => mark.textContent);
    expect(marked).toEqual(expect.arrayContaining(['serveurs', 'relancez']));
    expect(marked).not.toContain('or');
    expect(marked).not.toContain('barman');

    // The outline moves the reader: the words and the way back stay.
    await browser.click(within(screen.getByRole('navigation', { name: 'Sur cette page' })).getByRole('link', { name: '4.2 Erreurs fréquentes' }));
    expect(router.state.location.state).toEqual(expect.objectContaining({ fromSearch: expect.anything() }));
    await browser.click(screen.getByRole('button', { name: 'Retour aux résultats' }));
    await waitFor(() => expect(router.state.location.pathname).toBe('/search'));
    expect(router.state.location.search).toBe('?q=serveurs%20or%20relance%20-barman');
  });

  it('leads a copied link of a passage found to the results of its words', async () => {
    const document = view({ id: ID }, { mediaType: MARKDOWN, filename: 'r.md', extractionStatus: 'INDEXED', sectionCount: 3, language: 'fr' });
    server({
      [`GET /api/v1/documents/${ID}`]: () => json(document),
      [`GET /api/v1/documents/${ID}/access`]: () => json({ restricted: false, spaceMembers: 4, people: [] }),
      'GET /api/v1/spaces/by-slug/infrastructure': () => json(space()),
      [`GET /api/v1/documents/${ID}/versions/1/rendering`]: () => json({
        number: 1, renderer: 1, part: 1, partCount: 1, truncated: false, outline: [],
        html: '<section id="h-0"><p>Rien de ce qui est cherché.</p></section>',
      }),
    });
    const router = open(`/documents/${ID}?from=search&q=plan%20reprise`);
    const browser = userEvent.setup();

    await browser.click(await screen.findByRole('button', { name: 'Retour aux résultats' }));
    await waitFor(() => expect(router.state.location.pathname).toBe('/search'));
    expect(router.state.location.search).toBe('?q=plan+reprise');
  });
});

describe('a search that fails', () => {
  it('says that it took too long, without asking again on its own', async () => {
    const { asked } = server({
      'GET /api/v1/search': (request) => {
        asked.push(new URL(request.url));
        return json({ status: 503, code: 'search_timeout' }, 503);
      },
    });
    open('/search?q=sauvegarde');

    expect(await screen.findByText(/La recherche a pris trop de temps/)).toBeInTheDocument();
    expect(asked).toHaveLength(1);
  });
});
