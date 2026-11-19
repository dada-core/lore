import { QueryClientProvider } from '@tanstack/react-query';
import { cleanup, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter } from 'react-router';
import { RouterProvider } from 'react-router/dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { queryClient } from '../api/queryClient';
import { routes } from '../router';
import { christ, eli, folders, herve, item, limits, member, page, PDF, PROCEDURES, RUNBOOKS, space, SPACE_ID } from '../test/documents';
import { fakeServer, json, organization } from '../test/server';
import { ToastProvider } from '../ui/Toast';

type Routes = Parameters<typeof fakeServer>[0];

const documents = [
  item({ id: 'a', title: 'Plan reprise 2026' }),
  item({ id: 'b', title: 'Notes de passation', extractionStatus: 'PENDING', language: null, sectionCount: null }),
  item({ id: 'c', title: 'Rotation des certificats TLS', folderId: PROCEDURES, restricted: true, status: 'PUBLISHED', category: 'SECURITY' }),
  item({ id: 'd', title: 'Schéma de câblage salle B', folderId: PROCEDURES, mediaType: PDF, extractionStatus: 'SCANNED', pageCount: 4, language: null }),
  item({ id: 'e', title: 'Restaurer PostgreSQL', folderId: PROCEDURES, owner: null, language: 'en' }),
];
const summary = {
  total: 5, inProgress: 1, scanned: 1, failed: 0,
  folders: [{ folderId: null, count: 2 }, { folderId: PROCEDURES, count: 3 }],
};

function server(role = 'CONTRIBUTOR', routes: Routes = {}) {
  const asked: (string | null)[] = [];
  const server = fakeServer({
    'GET /api/v1/session': () => json({ setupRequired: false, organization, user: eli }),
    'GET /api/v1/spaces': () => json([space({ role, documentCount: 5 })]),
    'GET /api/v1/spaces/by-slug/infrastructure': () => json(space({ role, documentCount: 5 })),
    [`GET /api/v1/spaces/${SPACE_ID}/folders`]: () => json(folders),
    [`GET /api/v1/spaces/${SPACE_ID}/documents/summary`]: () => json(summary),
    [`GET /api/v1/spaces/${SPACE_ID}/documents`]: (request) => {
      const folder = new URL(request.url).searchParams.get('folder');
      asked.push(folder);
      if (folder === PROCEDURES) return json(page(documents.filter((one) => one.folderId === PROCEDURES)));
      return json(page(documents));
    },
    'GET /api/v1/imports/limits': () => json(limits),
    ...routes,
  });
  return { ...server, asked };
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

function titles(grid: HTMLElement): string[] {
  return within(grid).getAllByRole('link').map((link) => link.textContent ?? '');
}

beforeEach(() => {
  window.scrollTo = () => {};
});

afterEach(() => {
  // Unmounted first: a screen still shown would ask the real network again once the cache is cleared.
  cleanup();
  queryClient.clear();
  vi.unstubAllGlobals();
});

describe('library', () => {
  it('shows the documents grouped by folder, the root of the space included', async () => {
    server();
    open('/spaces/infrastructure');
    const grid = await screen.findByRole('table');

    expect(within(grid).getAllByRole('button', { expanded: true }).map((button) => button.textContent))
      .toEqual(['DossierSans dossier2', 'DossierProcédures3', 'DossierRunbooks0']);
    expect(titles(grid)).toEqual([
      'Plan reprise 2026', 'Notes de passation', 'Rotation des certificats TLS', 'Schéma de câblage salle B', 'Restaurer PostgreSQL',
    ]);
    expect(within(grid).getByText('Aucun document dans ce dossier.')).toBeInTheDocument();

    const restricted = within(grid).getByRole('link', { name: 'Rotation des certificats TLS' }).closest('tr') as HTMLElement;
    expect(within(restricted).getByRole('img', { name: 'Accès restreint' })).toBeInTheDocument();
    expect(within(restricted).getByText('Publié')).toBeInTheDocument();
    expect(within(restricted).getByText('Documentation de sécurité')).toBeInTheDocument();
    const scanned = within(grid).getByRole('link', { name: 'Schéma de câblage salle B' }).closest('tr') as HTMLElement;
    expect(within(scanned).getByText('Numérisé')).toBeInTheDocument();
    expect(within(scanned).getByText('PDF')).toBeInTheDocument();
    const pending = within(grid).getByRole('link', { name: 'Notes de passation' }).closest('tr') as HTMLElement;
    expect(within(pending).getByText('En cours')).toBeInTheDocument();
    const english = within(grid).getByRole('link', { name: 'Restaurer PostgreSQL' }).closest('tr') as HTMLElement;
    expect(within(english).getByText('Anglais')).toBeInTheDocument();
    expect(within(english).getByText('Ancien membre')).toBeInTheDocument();
    expect(within(grid).getByRole('link', { name: 'Plan reprise 2026' })).toHaveAttribute('href', '/documents/a');

    expect(await screen.findByText('5 documents')).toBeInTheDocument();
    expect(screen.getByText('1 en cours d’indexation')).toBeInTheDocument();
    expect(screen.getByText('1 numérisé, en partie non recherchable')).toBeInTheDocument();
  });

  it('folds a group, and filters by folder', async () => {
    const { asked } = server();
    const router = open('/spaces/infrastructure');
    const browser = userEvent.setup();
    const grid = await screen.findByRole('table');

    await browser.click(within(grid).getByRole('button', { name: /^Dossier.*Procédures/ }));
    expect(within(grid).getByRole('button', { name: /^Dossier.*Procédures/ })).toHaveAttribute('aria-expanded', 'false');
    expect(titles(grid)).toEqual(['Plan reprise 2026', 'Notes de passation']);

    await browser.click(screen.getByRole('button', { name: 'Filtrer par dossier' }));
    const menu = await screen.findByRole('menu');
    expect(within(menu).getAllByRole('menuitemradio').map((option) => option.textContent))
      .toEqual(['Tous les documents5', 'Sans dossier2', 'Procédures3', 'Runbooks0']);
    await browser.click(within(menu).getByRole('menuitemradio', { name: /^Procédures/ }));

    await waitFor(() => expect(router.state.location.search).toBe(`?folder=${PROCEDURES}`));
    await waitFor(() => expect(asked).toContain(PROCEDURES));
    expect(screen.getByRole('button', { name: 'Filtrer par dossier' })).toHaveTextContent('Dossier : Procédures');
    expect(await screen.findByText('3 documents')).toBeInTheDocument();
  });

  it('explains that a folder holding documents cannot be deleted', async () => {
    server('CONTRIBUTOR', {
      [`DELETE /api/v1/spaces/${SPACE_ID}/folders/${RUNBOOKS}`]: () => json({ status: 409, code: 'folder_not_empty' }, 409),
    });
    open('/spaces/infrastructure');
    const browser = userEvent.setup();
    await screen.findByRole('table');

    await browser.click(screen.getByRole('button', { name: 'Actions sur le dossier Procédures' }));
    await browser.click(await screen.findByRole('menuitem', { name: 'Supprimer' }));
    const notEmpty = await screen.findByRole('dialog', { name: 'Le dossier Procédures n’est pas vide' });
    expect(notEmpty).toHaveTextContent('Il contient 3 documents. Déplacez-les vers un autre dossier, puis supprimez le dossier.');
    await browser.click(within(notEmpty).getAllByRole('button', { name: 'Fermer' })[1]);
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());

    // A folder that looks empty may hold documents the person does not see: the server says so.
    await browser.click(screen.getByRole('button', { name: 'Actions sur le dossier Runbooks' }));
    await browser.click(await screen.findByRole('menuitem', { name: 'Supprimer' }));
    const confirm = await screen.findByRole('dialog', { name: /^Supprimer le dossier Runbooks/ });
    await browser.click(within(confirm).getByRole('button', { name: 'Supprimer' }));
    expect(await within(confirm).findByRole('alert')).toHaveTextContent('Ce dossier n’est pas vide');
  });

  it('lets a reader see the documents without folder actions or import', async () => {
    server('READER');
    open('/spaces/infrastructure');
    await screen.findByRole('table');

    expect(screen.queryByRole('button', { name: 'Nouveau dossier' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Importer/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Actions sur le dossier Procédures' })).not.toBeInTheDocument();
  });

  it('lists the restricted documents of the space for its maintainers', async () => {
    const restrictedAsked: (string | null)[] = [];
    server('MAINTAINER', {
      [`GET /api/v1/spaces/${SPACE_ID}/members`]: () => json([member(eli, 'MAINTAINER', true), member(christ, 'CONTRIBUTOR'), member(herve, 'READER')]),
      [`GET /api/v1/spaces/${SPACE_ID}/documents`]: (request) => {
        restrictedAsked.push(new URL(request.url).searchParams.get('restricted'));
        return json(page(documents.filter((one) => one.restricted)));
      },
    });
    open('/spaces/infrastructure/members');

    const section = await screen.findByRole('region', { name: /Documents restreints de cet espace/ });
    expect(await within(section).findByRole('link', { name: /Rotation des certificats TLS/ })).toHaveAttribute('href', '/documents/c');
    expect(within(section).getByText('Procédures')).toBeInTheDocument();
    expect(restrictedAsked).toContain('true');
  });

  it('keeps the restricted documents from the other members', async () => {
    server('CONTRIBUTOR', {
      [`GET /api/v1/spaces/${SPACE_ID}/members`]: () => json([member(eli, 'CONTRIBUTOR', true)]),
    });
    open('/spaces/infrastructure/members');
    await screen.findByRole('heading', { name: 'Membres de l’espace Infrastructure' });
    expect(screen.queryByRole('region', { name: /Documents restreints/ })).not.toBeInTheDocument();
  });
});
