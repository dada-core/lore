import { QueryClientProvider } from '@tanstack/react-query';
import { act, cleanup, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter } from 'react-router';
import { RouterProvider } from 'react-router/dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { queryClient } from '../api/queryClient';
import { routes } from '../router';
import { amenan, fakeServer, json, noContent, organization } from '../test/server';
import { ToastProvider } from '../ui/Toast';
import { slugify } from './spaces';

const eli = { ...amenan, id: '0193b1f2-0000-7000-8000-000000000002', displayName: 'Eli Brou', email: 'eli.brou@dada.group', role: 'MEMBER' };

function space(overrides: Record<string, unknown> = {}) {
  return {
    id: '0193b1f2-0000-7000-8000-0000000000a1', name: 'Infrastructure', slug: 'infrastructure', description: null,
    color: '#13705E', role: 'MAINTAINER', memberCount: 1, createdAt: '2026-11-13T09:00:00Z',
    lastActivityAt: '2026-11-13T09:00:00Z', archivedAt: null, ...overrides,
  };
}

/** An empty library: no documents yet. */
function noDocuments(spaceId: string) {
  return {
    [`GET /api/v1/spaces/${spaceId}/documents`]: () => json({ items: [], page: 0, size: 200, total: 0 }),
    [`GET /api/v1/spaces/${spaceId}/documents/summary`]: () => json({ total: 0, inProgress: 0, scanned: 0, failed: 0, folders: [] }),
  };
}

function member(overrides: Record<string, unknown> = {}) {
  return {
    id: eli.id, displayName: 'Eli Brou', email: 'eli.brou@dada.group', role: 'MAINTAINER', active: true, self: true,
    addedAt: '2026-11-13T09:00:00Z', ...overrides,
  };
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
  // Unmounted first: a screen still shown would ask the real network again once the cache is cleared.
  cleanup();
  queryClient.clear();
  vi.unstubAllGlobals();
});

describe('spaces', () => {
  it('derives the address of a space from its name, as the server does', () => {
    expect(slugify('Réseau & Sécurité')).toBe('reseau-securite');
    expect(slugify('  --Applications métier__2026--  ')).toBe('applications-metier-2026');
  });

  it('creates a space from the sidebar and opens it', async () => {
    const created: unknown[] = [];
    let spaces: ReturnType<typeof space>[] = [];
    fakeServer({
      'GET /api/v1/session': () => json({ setupRequired: false, organization, user: eli }),
      'GET /api/v1/spaces': () => json(spaces),
      'POST /api/v1/spaces': async (request) => {
        created.push(await request.json());
        spaces = [space({ name: 'Applications métier', slug: 'applications-metier', color: '#3F6C9A' })];
        return json(spaces[0], 201);
      },
      'GET /api/v1/spaces/by-slug/applications-metier': () => json(spaces[0]),
      [`GET /api/v1/spaces/${space().id}/folders`]: () => json([]),
      ...noDocuments(space().id),
    });
    const router = open('/');
    const browser = userEvent.setup();

    const nav = await screen.findByRole('navigation', { name: 'Espaces' });
    await browser.click(within(nav).getByRole('button', { name: 'Créer un espace' }));
    const dialog = await screen.findByRole('dialog', { name: 'Créer un espace' });
    await browser.type(within(dialog).getByLabelText('Nom'), 'Applications métier');
    expect(within(dialog).getByLabelText('Adresse')).toHaveValue('applications-metier');
    await browser.click(within(dialog).getByLabelText('Bleu acier'));
    await browser.click(within(dialog).getByRole('button', { name: 'Créer l’espace' }));

    expect(await screen.findByRole('heading', { name: 'Applications métier est vide' })).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/spaces/applications-metier');
    // The address shown is the one derived from the name: the server derives it too, with -2 if it is taken.
    expect(created).toEqual([{ name: 'Applications métier', description: null, slug: null, color: '#3F6C9A' }]);
    expect(screen.getByRole('link', { name: 'Paramètres' })).toBeInTheDocument();
    // The new space took the place of the window: going back leads to the page it was opened from.
    await act(() => router.navigate(-1));
    expect(router.state.location.pathname).toBe('/');
    expect(router.state.location.search).toBe('');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('closes the window of a new space without leaving a step in the history', async () => {
    fakeServer({
      'GET /api/v1/session': () => json({ setupRequired: false, organization, user: eli }),
      'GET /api/v1/spaces': () => json([]),
    });
    const router = open('/');
    const browser = userEvent.setup();

    const nav = await screen.findByRole('navigation', { name: 'Espaces' });
    await browser.click(within(nav).getByRole('button', { name: 'Créer un espace' }));
    const dialog = await screen.findByRole('dialog', { name: 'Créer un espace' });
    await browser.click(within(dialog).getByRole('button', { name: 'Annuler' }));

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(router.state.location.search).toBe('');
    expect(router.state.historyAction).toBe('POP');
  });

  it('shows the error of the server under the address', async () => {
    const sent: unknown[] = [];
    fakeServer({
      'GET /api/v1/session': () => json({ setupRequired: false, organization, user: eli }),
      'GET /api/v1/spaces': () => json([space()]),
      'POST /api/v1/spaces': async (request) => {
        sent.push(await request.json());
        return json({
          status: 400, code: 'invalid_request', errors: [{ field: 'slug', code: 'slug_taken', params: {} }],
        }, 400);
      },
    });
    open('/spaces?open=create-space');
    const browser = userEvent.setup();
    const dialog = await screen.findByRole('dialog', { name: 'Créer un espace' });
    await browser.type(within(dialog).getByLabelText('Nom'), 'Infrastructure réseau');
    const address = within(dialog).getByLabelText('Adresse');
    await browser.clear(address);
    await browser.type(address, 'infrastructure');
    await browser.click(within(dialog).getByRole('button', { name: 'Créer l’espace' }));

    await waitFor(() => expect(address).toBeInvalid());
    expect(address).toHaveAccessibleDescription(/^Cette adresse est déjà utilisée par un autre espace\./);
    expect(sent).toEqual([expect.objectContaining({ slug: 'infrastructure' })]);
  });

  it('lets a reader see the space, but neither its settings nor the creation of folders', async () => {
    const reader = space({ role: 'READER' });
    fakeServer({
      'GET /api/v1/session': () => json({ setupRequired: false, organization, user: eli }),
      'GET /api/v1/spaces': () => json([reader]),
      'GET /api/v1/spaces/by-slug/infrastructure': () => json(reader),
      [`GET /api/v1/spaces/${reader.id}/folders`]: () => json([{ id: 'f1', name: 'Procédures', createdAt: '2026-11-13T09:00:00Z' }]),
      ...noDocuments(reader.id),
    });
    open('/spaces/infrastructure');

    expect(await screen.findByText('Procédures')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Nouveau dossier' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Actions sur le dossier Procédures' })).not.toBeInTheDocument();
    const nav = screen.getByRole('navigation', { name: 'Espaces' });
    expect(within(nav).getByRole('link', { name: 'Membres' })).toBeInTheDocument();
    expect(within(nav).queryByRole('link', { name: 'Paramètres' })).not.toBeInTheDocument();
  });

  it('shows a reader the settings of the space without letting them change anything', async () => {
    const reader = space({ role: 'READER' });
    fakeServer({
      'GET /api/v1/session': () => json({ setupRequired: false, organization, user: eli }),
      'GET /api/v1/spaces': () => json([reader]),
      'GET /api/v1/spaces/by-slug/infrastructure': () => json(reader),
    });
    open('/spaces/infrastructure/settings');

    expect(await screen.findByRole('alert')).toHaveTextContent('Réservé aux responsables de l’espace');
    expect(screen.queryByLabelText('Nom')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Archiver|Supprimer/ })).not.toBeInTheDocument();
  });

  it('shows the same page for a space that does not exist or cannot be seen', async () => {
    fakeServer({
      'GET /api/v1/session': () => json({ setupRequired: false, organization, user: eli }),
      'GET /api/v1/spaces': () => json([]),
      'GET /api/v1/spaces/by-slug/confidentiel': () => json({ status: 404, code: 'not_found' }, 404),
    });
    open('/spaces/confidentiel');

    expect(await screen.findByRole('heading', { name: 'Cette page est introuvable' })).toBeInTheDocument();
  });

  it('keeps the last maintainer of a space', async () => {
    const infrastructure = space();
    fakeServer({
      'GET /api/v1/session': () => json({ setupRequired: false, organization, user: eli }),
      'GET /api/v1/spaces': () => json([infrastructure]),
      'GET /api/v1/spaces/by-slug/infrastructure': () => json(infrastructure),
      [`GET /api/v1/spaces/${infrastructure.id}/members`]: () => json([member()]),
      [`DELETE /api/v1/spaces/${infrastructure.id}/members/${eli.id}`]: () => json({ status: 409, code: 'last_maintainer' }, 409),
    });
    open('/spaces/infrastructure/members');
    const browser = userEvent.setup();

    expect(await screen.findByRole('heading', { name: 'Membres de l’espace Infrastructure' })).toBeInTheDocument();
    await browser.click(await screen.findByRole('button', { name: 'Quitter l’espace' }));
    // French typography puts a narrow no-break space before the question mark.
    const dialog = await screen.findByRole('dialog', { name: /^Quitter Infrastructure/ });
    await browser.click(within(dialog).getByRole('button', { name: 'Quitter l’espace' }));

    expect(await within(dialog).findByRole('alert')).toHaveTextContent('L’espace doit garder au moins un responsable.');
  });

  it('deletes a space once its name is typed', async () => {
    const infrastructure = space();
    let deleted = false;
    fakeServer({
      'GET /api/v1/session': () => json({ setupRequired: false, organization, user: eli }),
      'GET /api/v1/spaces': () => json(deleted ? [] : [infrastructure]),
      'GET /api/v1/spaces/by-slug/infrastructure': () => json(infrastructure),
      [`DELETE /api/v1/spaces/${infrastructure.id}`]: () => {
        deleted = true;
        return noContent();
      },
    });
    const router = open('/spaces/infrastructure/settings');
    const browser = userEvent.setup();

    await browser.click(await screen.findByRole('button', { name: 'Supprimer' }));
    const dialog = await screen.findByRole('dialog', { name: 'Supprimer l’espace' });
    const confirm = within(dialog).getByRole('button', { name: 'Supprimer définitivement' });
    expect(confirm).toBeDisabled();
    await browser.type(within(dialog).getByLabelText('Pour confirmer, saisissez Infrastructure'), 'Infrastructure');
    await browser.click(confirm);

    await waitFor(() => expect(router.state.location.pathname).toBe('/spaces'));
    expect(deleted).toBe(true);
    expect(await screen.findByText('Infrastructure a été supprimé')).toBeInTheDocument();
  });
});
