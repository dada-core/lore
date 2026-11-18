import { QueryClientProvider } from '@tanstack/react-query';
import { act, cleanup, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter } from 'react-router';
import { RouterProvider } from 'react-router/dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { queryClient } from '../api/queryClient';
import { routes } from '../router';
import { amenan, fakeServer, json, organization } from '../test/server';
import { eli, folders, limits, MARKDOWN, page, space, SPACE_ID, view } from '../test/documents';
import { fakeUploads } from '../test/xhr';
import { ToastProvider } from '../ui/Toast';

vi.mock('./reader/pdf', () => ({ ASSETS: '/pdfjs/', loadPdf: () => new Promise(() => {}) }));

type Routes = Parameters<typeof fakeServer>[0];
type View = ReturnType<typeof view>;

const ID = 'd0000000-0000-7000-8000-000000000001';
const REPLACEMENT = 'd0000000-0000-7000-8000-000000000009';

function version(number: number, overrides: Record<string, unknown> = {}) {
  return {
    id: `e0000000-0000-7000-8000-00000000000${number}`, number, filename: 'restaurer.md', mediaType: MARKDOWN, sizeBytes: 18_000,
    language: 'fr', pageCount: null, sectionCount: 14, extractionStatus: 'INDEXED', extractionError: null, textTruncated: false,
    extractedAt: '2026-11-10T09:00:00Z', createdAt: `2026-0${number + 6}-02T09:00:00Z`, author: { id: eli.id, displayName: eli.displayName },
    sha256: `${number}`.repeat(64), fileAvailable: true, note: number === 1 ? null : `Note de la version ${number}`,
    changeRatio: number === 1 ? 1 : 0.2,
    ...overrides,
  };
}

/** The page of a document and what it asks; returns the bodies sent, by method and route. */
function server(document: View, routes: Routes = {}, user: Record<string, unknown> = eli) {
  const sent: Record<string, unknown[]> = {};
  let current = document;
  fakeServer({
    'GET /api/v1/session': () => json({ setupRequired: false, organization, user }),
    'GET /api/v1/spaces': () => json([space()]),
    [`GET /api/v1/documents/${ID}`]: () => json(current),
    [`GET /api/v1/documents/${ID}/access`]: () => json({ restricted: false, spaceMembers: 4, people: [] }),
    [`GET /api/v1/documents/${ID}/versions`]: () => json(page([current.currentVersion])),
    [`GET /api/v1/documents/${ID}/activity`]: () => json({ items: [], next: null }),
    [`GET /api/v1/documents/${ID}/versions/1/rendering`]: () => json({
      number: 1, renderer: 1, part: 1, partCount: 1, truncated: false, outline: [], anchor: null,
      html: '<section id="h-0"><p>Texte.</p></section>',
    }),
    [`GET /api/v1/spaces/${SPACE_ID}/folders`]: () => json(folders),
    [`GET /api/v1/spaces/${SPACE_ID}/documents`]: () => json(page([])),
    [`GET /api/v1/spaces/${SPACE_ID}/documents/summary`]: () => json({ total: 0, inProgress: 0, scanned: 0, failed: 0, archived: 0, folders: [] }),
    'GET /api/v1/spaces/by-slug/infrastructure': () => json(space()),
    'GET /api/v1/imports/limits': () => json(limits),
    [`PUT /api/v1/documents/${ID}/status`]: async (request) => {
      const body = (await request.json()) as { status: string; replacedBy?: string };
      (sent.status ??= []).push(body);
      current = {
        ...current,
        status: body.status,
        statusChangedAt: '2026-11-17T09:00:00Z',
        replacedBy: body.replacedBy ? { id: body.replacedBy, title: 'Restaurer une base' } : null,
      };
      return json(current);
    },
    ...routes,
  });
  return sent;
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
  window.scrollTo = () => {};
});

afterEach(() => {
  cleanup();
  queryClient.clear();
  vi.unstubAllGlobals();
});

describe('a new version', () => {
  it('is imported with what changes and the status asked, the current one staying until its text is extracted', async () => {
    const document = view({ status: 'PUBLISHED' }, { mediaType: MARKDOWN, filename: 'restaurer.md', extractionStatus: 'INDEXED' });
    server(document);
    const uploads = fakeUploads();
    open(`/documents/${ID}`);
    const browser = userEvent.setup();

    await browser.click(await screen.findByRole('button', { name: 'Nouvelle version' }));
    const dialog = await screen.findByRole('dialog', { name: /Nouvelle version de «\sPlan reprise 2026\s»/ });
    const file = new File(['# Restaurer\n\nNouveau texte.'], 'restaurer-v2.pdf', { type: 'application/pdf' });
    await browser.upload(within(dialog).getByLabelText(/Déposez le fichier de la version 2/), file);
    // Another format than the current version: said before sending.
    expect(within(dialog).getByText(/Le format change\s:\sMarkdown, puis PDF\./)).toBeInTheDocument();
    await browser.type(within(dialog).getByLabelText('Ce qui change'), 'Ajout de la vérification des rôles');
    await browser.click(within(dialog).getByRole('checkbox', { name: 'Conserver le statut Publié' }));
    expect(within(dialog).getByText(/La version 1 reste consultable/)).toBeInTheDocument();
    await browser.click(within(dialog).getByRole('button', { name: 'Importer la version 2' }));

    expect(uploads.sent).toHaveLength(1);
    const [sent] = uploads.sent;
    expect(sent.url).toBe(`/api/v1/documents/${ID}/versions`);
    expect(sent.field('note')).toEqual(['Ajout de la vérification des rôles']);
    expect(sent.field('status')).toEqual(['DRAFT']);
    expect(sent.filename).toBe('restaurer-v2.pdf');
    await act(async () => sent.respond(201, {
      ...document,
      versionCount: 2,
      newerVersion: { number: 2, extractionStatus: 'PENDING', extractionError: null, createdAt: '2026-11-18T09:00:00Z', author: null },
    }));

    expect(await screen.findByText(/Version importée\s:\sextraction du texte en cours/)).toBeInTheDocument();
    expect(screen.getByText('Une nouvelle version est en cours de traitement.')).toBeInTheDocument();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('says that the file is the same as the latest version', async () => {
    server(view({}, { mediaType: MARKDOWN, filename: 'restaurer.md', extractionStatus: 'INDEXED' }));
    const uploads = fakeUploads();
    open(`/documents/${ID}`);
    const browser = userEvent.setup();

    await browser.click(await screen.findByRole('button', { name: 'Nouvelle version' }));
    const dialog = await screen.findByRole('dialog');
    await browser.upload(within(dialog).getByLabelText(/Déposez le fichier/), new File(['x'], 'restaurer.md', { type: 'text/markdown' }));
    // A draft keeps its status: nothing to choose.
    expect(within(dialog).queryByRole('checkbox')).not.toBeInTheDocument();
    await browser.click(within(dialog).getByRole('button', { name: 'Importer la version 2' }));
    await act(async () => uploads.sent[0].respond(409, { status: 409, code: 'same_content', version: 1 }));

    expect(await within(dialog).findByRole('alert')).toHaveTextContent('Ce fichier est identique à la version 1.');
    expect(uploads.sent[0].field('status')).toEqual([]);
  });

  it('that failed is told to who may import another, with Retry for a passing failure', async () => {
    server(view({
      actions: { ...view().actions, retry: true },
      newerVersion: { number: 2, extractionStatus: 'FAILED', extractionError: 'timeout', createdAt: '2026-11-18T09:00:00Z', author: null },
    }, { mediaType: MARKDOWN, filename: 'restaurer.md', extractionStatus: 'INDEXED' }));
    open(`/documents/${ID}`);

    expect(await screen.findByText(/La version 2 n’a pas pu être traitée/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Relancer' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Importer une autre version' })).toBeInTheDocument();
  });
});

describe('the status of a document', () => {
  it('becomes obsolete with the document that replaces it; Archived is for the maintainers', async () => {
    const sent = server(view({ actions: { ...view().actions, archive: false } }, { extractionStatus: 'INDEXED' }), {
      'GET /api/v1/search/titles': () => json([
        { id: ID, title: 'Plan reprise 2026', titleHighlights: [], status: 'DRAFT', mediaType: MARKDOWN,
          space: { id: SPACE_ID, name: 'Infrastructure', slug: 'infrastructure', archived: false }, folder: null },
        { id: REPLACEMENT, title: 'Restaurer une base', titleHighlights: [], status: 'PUBLISHED', mediaType: MARKDOWN,
          space: { id: SPACE_ID, name: 'Infrastructure', slug: 'infrastructure', archived: false }, folder: null },
      ]),
    });
    open(`/documents/${ID}`);
    const browser = userEvent.setup();

    await browser.click(await screen.findByRole('button', { name: /Changer le statut\s:\sBrouillon/ }));
    const dialog = await screen.findByRole('dialog', { name: 'Changer le statut' });
    expect(within(dialog).getByRole('radio', { name: /Archivé/ })).toBeDisabled();
    expect(within(dialog).getByText(/Réservé aux responsables de l’espace\./)).toBeInTheDocument();
    await browser.click(within(dialog).getByRole('radio', { name: /Obsolète/ }));
    await browser.type(within(dialog).getByLabelText('Remplacé par'), 'Rest');
    // The document itself is never offered.
    const offered = await within(dialog).findByRole('button', { name: /Restaurer une base/ });
    expect(within(dialog).queryByRole('button', { name: /Plan reprise 2026/ })).not.toBeInTheDocument();
    await browser.click(offered);
    await browser.click(within(dialog).getByRole('button', { name: 'Enregistrer' }));

    await waitFor(() => expect(sent.status).toEqual([{ status: 'OBSOLETE', replacedBy: REPLACEMENT }]));
    expect(await screen.findByText(/Ce document est obsolète depuis le 17 nov\. 2026\./)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Restaurer une base' })).toHaveAttribute('href', `/documents/${REPLACEMENT}`);
  });

  it('is archived from the menu by a maintainer, who reads the effect first', async () => {
    const sent = server(view({}, { extractionStatus: 'INDEXED' }));
    open(`/documents/${ID}`);
    const browser = userEvent.setup();

    await browser.click(await screen.findByRole('button', { name: 'Autres actions' }));
    await browser.click(await screen.findByRole('menuitem', { name: 'Archiver' }));
    const dialog = await screen.findByRole('dialog', { name: 'Changer le statut' });
    expect(within(dialog).getByRole('radio', { name: /Archivé/ })).toBeChecked();
    expect(within(dialog).getByText(/Retiré des listes et de la recherche/)).toBeInTheDocument();
    await browser.click(within(dialog).getByRole('button', { name: 'Enregistrer' }));

    await waitFor(() => expect(sent.status).toEqual([{ status: 'ARCHIVED' }]));
    expect(await screen.findByText(/Ce document est archivé depuis le 17 nov\. 2026/)).toBeInTheDocument();
  });
});

describe('the history of a document', () => {
  it('shows its versions in strata, from the keyboard, and an older one in its own page', async () => {
    const versions = [version(3), version(2), version(1)];
    const document = view({ versionCount: 3 }, version(3));
    server(document, {
      [`GET /api/v1/documents/${ID}/versions`]: () => json(page(versions)),
      [`GET /api/v1/documents/${ID}/versions/2`]: () => json(versions[1]),
      [`GET /api/v1/documents/${ID}/versions/3/rendering`]: () => json({
        number: 3, renderer: 1, part: 1, partCount: 1, truncated: false, outline: [], anchor: null,
        html: '<section id="h-0"><p>Version trois.</p></section>',
      }),
      [`GET /api/v1/documents/${ID}/versions/2/rendering`]: () => json({
        number: 2, renderer: 1, part: 1, partCount: 1, truncated: false, outline: [], anchor: null,
        html: '<section id="h-0"><p>Version deux.</p></section>',
      }),
    });
    const router = open(`/documents/${ID}`);
    const browser = userEvent.setup();

    const strata = await screen.findByRole('group', { name: 'Historique des versions, de la plus récente à la plus ancienne' });
    const bands = within(strata).getAllByRole('button');
    expect(bands).toHaveLength(3);
    expect(bands[0]).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByText('Version 3, actuelle')).toBeInTheDocument();
    bands[0].focus();
    await browser.keyboard('{ArrowDown}');
    expect(bands[1]).toHaveAttribute('aria-pressed', 'true');
    expect(bands[1]).toHaveFocus();
    expect(screen.getByText('Note de la version 2')).toBeInTheDocument();
    await browser.click(screen.getByRole('link', { name: 'Afficher' }));

    await waitFor(() => expect(router.state.location.pathname).toBe(`/documents/${ID}/versions/2`));
    expect(await screen.findByText(/Vous consultez la version 2 du 2 août 2026\. La version actuelle est la 3\./)).toBeInTheDocument();
    expect(await screen.findByText('Version deux.')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Revenir à la version actuelle' })).toHaveAttribute('href', `/documents/${ID}`);
  });

  it('tells what was done to the document, never who was chosen for an access', async () => {
    server(view({}, { extractionStatus: 'INDEXED' }), {
      [`GET /api/v1/documents/${ID}/activity`]: (request) => json(new URL(request.url).searchParams.get('before')
        ? { items: [{ id: 1, occurredAt: '2026-11-01T09:00:00Z', actor: { id: eli.id, displayName: eli.displayName }, action: 'document.imported', details: {} }], next: null }
        : {
          items: [
            { id: 4, occurredAt: '2026-11-17T09:00:00Z', actor: { id: eli.id, displayName: eli.displayName }, action: 'document.status_changed',
              details: { before: 'PUBLISHED', after: 'OBSOLETE', replacedBy: { id: REPLACEMENT, title: 'Restaurer une base' } } },
            { id: 3, occurredAt: '2026-11-16T09:00:00Z', actor: { id: eli.id, displayName: eli.displayName }, action: 'document.access_changed',
              details: { before: 'space', after: 'restricted', added: 2, removed: 0 } },
            { id: 2, occurredAt: '2026-11-15T09:00:00Z', actor: null, action: 'document.version_imported', details: { version: 2 } },
            { id: 6, occurredAt: '2026-11-14T09:00:00Z', actor: { id: eli.id, displayName: eli.displayName }, action: 'document.moved',
              details: { to: { id: 'f1', deleted: true } } },
            { id: 5, occurredAt: '2026-11-13T09:00:00Z', actor: { id: eli.id, displayName: eli.displayName }, action: 'document.moved',
              details: {} },
          ],
          next: 'cursor',
        }),
    });
    open(`/documents/${ID}`);
    const browser = userEvent.setup();

    const activity = await screen.findByRole('region', { name: 'Activité' });
    expect(await within(activity).findByText(/a changé le statut\s:\sObsolète/)).toBeInTheDocument();
    expect(within(activity).getByRole('link', { name: 'Restaurer une base' })).toBeInTheDocument();
    expect(within(activity).getByText(/a restreint l’accès à 2 personnes/)).toBeInTheDocument();
    expect(within(activity).getByText(/a importé la version 2/)).toBeInTheDocument();
    expect(within(activity).getByText(/a déplacé le document dans un dossier supprimé depuis/)).toBeInTheDocument();
    expect(within(activity).getByText(/a placé le document hors de tout dossier/)).toBeInTheDocument();
    await browser.click(within(activity).getByRole('button', { name: 'Afficher plus' }));
    expect(await within(activity).findByText(/a importé le document/)).toBeInTheDocument();
  });
});

describe('Administration › Audit log', () => {
  it('shows the operations as sentences, filters them, and exports them', async () => {
    const asked: URL[] = [];
    fakeServer({
      'GET /api/v1/session': () => json({ setupRequired: false, organization, user: amenan }),
      'GET /api/v1/spaces': () => json([space()]),
      'GET /api/v1/admin/members': () => json(page([])),
      'GET /api/v1/admin/audit': (request) => {
        asked.push(new URL(request.url));
        return json({
          items: [
            { id: 2, occurredAt: '2026-11-17T09:00:00Z', actor: { id: eli.id, displayName: eli.displayName, email: eli.email },
              action: 'document.status_changed', category: 'status', resource: { type: 'document', id: ID }, ip: '10.0.0.4',
              details: { title: 'Barman', before: 'PUBLISHED', after: 'OBSOLETE' } },
            { id: 1, occurredAt: '2026-11-16T09:00:00Z', actor: null, action: 'lore.future_action', category: 'other',
              resource: null, ip: null, details: {} },
          ],
          next: null,
        });
      },
    });
    const assign = vi.fn();
    vi.stubGlobal('location', { ...window.location, assign });
    open('/admin/audit');
    const browser = userEvent.setup();

    expect(await screen.findByText(/A changé le statut de «\sBarman\s»\s:\sObsolète/)).toBeInTheDocument();
    expect(screen.getByText('lore.future_action')).toBeInTheDocument();
    expect(screen.getByText('Système')).toBeInTheDocument();
    expect(screen.queryByText('10.0.0.4')).not.toBeInTheDocument();
    await browser.click(screen.getByRole('checkbox', { name: 'Afficher l’adresse IP' }));
    expect(screen.getByText('10.0.0.4')).toBeInTheDocument();
    expect(asked[0].searchParams.get('from')).not.toBeNull();

    await browser.click(screen.getByRole('button', { name: 'Catégorie' }));
    await browser.click(await screen.findByRole('menuitemradio', { name: 'Statut' }));
    await waitFor(() => expect(asked.at(-1)?.searchParams.get('category')).toBe('status'));
    await browser.click(screen.getByRole('button', { name: 'Exporter en CSV' }));
    expect(assign).toHaveBeenCalledWith(expect.stringMatching(/^\/api\/v1\/admin\/audit\/export\?category=status&from=/));
  });
});

describe('the library by status', () => {
  it('shows a column per status, Archived for the maintainers only', async () => {
    const asked: string[] = [];
    const library = (role: string) => fakeServer({
      'GET /api/v1/session': () => json({ setupRequired: false, organization, user: eli }),
      'GET /api/v1/spaces': () => json([space({ role })]),
      'GET /api/v1/spaces/by-slug/infrastructure': () => json(space({ role })),
      [`GET /api/v1/spaces/${SPACE_ID}/folders`]: () => json(folders),
      [`GET /api/v1/spaces/${SPACE_ID}/documents/summary`]: () => json({ total: 1, inProgress: 0, scanned: 0, failed: 0, archived: role === 'MAINTAINER' ? 1 : 0, folders: [] }),
      [`GET /api/v1/spaces/${SPACE_ID}/documents`]: (request) => {
        const status = new URL(request.url).searchParams.getAll('status');
        asked.push(status.join(','));
        const title = status[0] === 'PUBLISHED' ? 'Restaurer une base' : status[0] === 'ARCHIVED' ? 'Procédure VPN 2024' : null;
        return json(page(title ? [{ ...view({ title }), version: 4, filename: 'r.md', mediaType: MARKDOWN, sizeBytes: 1, extractionStatus: 'INDEXED' }] : []));
      },
    });
    library('MAINTAINER');
    open('/spaces/infrastructure?view=kanban');

    for (const column of ['Brouillon', 'Publié', 'Obsolète', 'Archivé']) {
      expect(await screen.findByRole('region', { name: new RegExp(column) })).toBeInTheDocument();
    }
    expect(await screen.findByRole('link', { name: /Restaurer une base/ })).toBeInTheDocument();
    expect(await screen.findByRole('link', { name: /Procédure VPN 2024/ })).toBeInTheDocument();
    expect(asked).toEqual(expect.arrayContaining(['DRAFT', 'PUBLISHED', 'OBSOLETE', 'ARCHIVED']));
    expect(screen.getByRole('link', { name: 'Kanban par statut' })).toHaveAttribute('aria-current', 'page');

    cleanup();
    queryClient.clear();
    library('CONTRIBUTOR');
    open('/spaces/infrastructure?view=kanban');
    expect(await screen.findByRole('region', { name: /Obsolète/ })).toBeInTheDocument();
    expect(screen.queryByRole('region', { name: /Archivé/ })).not.toBeInTheDocument();
  });
});
