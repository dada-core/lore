import { QueryClientProvider } from '@tanstack/react-query';
import { cleanup, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter } from 'react-router';
import { RouterProvider } from 'react-router/dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { keys, queryClient } from '../api/queryClient';
import { routes } from '../router';
import {
  amenanPerson, christ, eli, folders, herve, item, limits, member, page, PDF, PROCEDURES, space, SPACE_ID, view,
} from '../test/documents';
import { fakeServer, json, noContent, organization } from '../test/server';
import { ToastProvider } from '../ui/Toast';
import { timing } from './documents';

// pdf.js does not run in jsdom: its viewer is tested with a fake in reader.test.tsx.
vi.mock('./reader/pdf', () => ({ ASSETS: '/pdfjs/', loadPdf: () => new Promise(() => {}) }));

type Routes = Parameters<typeof fakeServer>[0];
type View = ReturnType<typeof view>;

const ID = 'd0000000-0000-7000-8000-000000000001';
const readOnly = {
  edit: false, delete: false, manageAccess: false, retry: false, changeOwner: false, newVersion: false, changeStatus: false,
  archive: false,
};

/** The page of a document; returns the bodies sent to it, by method. */
function server(document: View, routes: Routes = {}) {
  const sent: Record<string, unknown[]> = {};
  const record = async (key: string, request: Request) => {
    (sent[key] ??= []).push(await request.json());
  };
  let current = document;
  fakeServer({
    'GET /api/v1/session': () => json({ setupRequired: false, organization, user: eli }),
    'GET /api/v1/spaces': () => json([space()]),
    [`GET /api/v1/documents/${ID}`]: () => json(current),
    [`GET /api/v1/documents/${ID}/access`]: () => json({ restricted: false, spaceMembers: 4, people: [] }),
    [`GET /api/v1/documents/${ID}/versions`]: () => json(page([current.currentVersion])),
    [`GET /api/v1/documents/${ID}/activity`]: () => json({ items: [], next: null }),
    [`GET /api/v1/spaces/${SPACE_ID}/folders`]: () => json(folders),
    [`GET /api/v1/spaces/${SPACE_ID}/documents`]: () => json(page([])),
    [`GET /api/v1/spaces/${SPACE_ID}/documents/summary`]: () => json({ total: 0, inProgress: 0, scanned: 0, failed: 0, folders: [] }),
    'GET /api/v1/spaces/by-slug/infrastructure': () => json(space()),
    'GET /api/v1/imports/limits': () => json(limits),
    [`PATCH /api/v1/documents/${ID}`]: async (request) => {
      const changes = (await request.clone().json()) as Record<string, unknown>;
      await record('PATCH', request);
      current = { ...current, ...changes };
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

const refreshing = timing.refresh;

afterEach(() => {
  // Unmounted first: a screen still shown would ask the real network again once the cache is cleared.
  cleanup();
  timing.refresh = refreshing;
  queryClient.clear();
  vi.unstubAllGlobals();
});

describe('page of a document', () => {
  it('says what to do after a final failure, and offers only what the person may do', async () => {
    server(view(
      { folder: { id: PROCEDURES, name: 'Procédures' }, actions: readOnly },
      { extractionStatus: 'FAILED', extractionError: 'encrypted' },
    ));
    open(`/documents/${ID}`);

    expect(await screen.findByRole('heading', { level: 1, name: 'Plan reprise 2026' })).toBeInTheDocument();
    const crumbs = screen.getByRole('navigation', { name: 'Fil d’Ariane' });
    expect(within(crumbs).getAllByRole('link').map((link) => link.textContent)).toEqual(['Espaces', 'Infrastructure', 'Procédures']);
    expect(within(crumbs).getByRole('link', { name: 'Procédures' })).toHaveAttribute('href', `/spaces/infrastructure?folder=${PROCEDURES}`);
    expect(screen.getByText(/^Échec de l’extraction\s: le fichier est protégé par un mot de passe\. Retirez la protection, puis importez le fichier à nouveau\.$/))
      .toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Relancer' })).not.toBeInTheDocument();
    // Of the other actions, a reader has what does not change the document.
    await userEvent.setup().click(screen.getByRole('button', { name: 'Autres actions' }));
    expect(screen.getAllByRole('menuitem').map((item) => item.textContent)).toEqual(['Copier le lien', 'Imprimer']);
    await userEvent.setup().keyboard('{Escape}');
    expect(screen.queryByRole('button', { name: 'Modifier le titre' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /description/ })).not.toBeInTheDocument();
    // No content after a failure: the banner, and the original to download.
    expect(screen.getByRole('link', { name: 'Télécharger l’original' })).toHaveAttribute('href', `/api/v1/documents/${ID}/versions/1/file`);

    const properties = screen.getByRole('complementary', { name: 'Propriétés' });
    expect(within(properties).getByText('Brouillon')).toBeInTheDocument();
    expect(within(properties).getByText('Eli Brou', { selector: '.who span' })).toBeInTheDocument();
    expect(within(properties).getByText('Sans catégorie')).toBeInTheDocument();
    // The format says its size: "Word, 1,2 Mo".
    expect(within(properties).getByText(/^Word, 1,2\sMo$/)).toBeInTheDocument();
    expect(within(properties).getByText('Échec')).toBeInTheDocument();
    expect(within(properties).getByText('Version 1')).toBeInTheDocument();
    expect(within(properties).getByText('plan-reprise-2026.docx')).toBeInTheDocument();
    expect(await within(properties).findByText(/Visible par les 4 membres de l’espace Infrastructure/)).toBeInTheDocument();
  });

  it('tries the extraction again after a failure that may pass', async () => {
    let retried = false;
    server(view({ actions: { ...readOnly, edit: true, retry: true } }, { extractionStatus: 'FAILED', extractionError: 'extractor_crashed' }), {
      [`POST /api/v1/documents/${ID}/extraction/retry`]: () => {
        retried = true;
        return json(view());
      },
    });
    open(`/documents/${ID}`);
    const browser = userEvent.setup();

    expect(await screen.findByText(/le processus d’extraction s’est arrêté\./)).toBeInTheDocument();
    await browser.click(screen.getByRole('button', { name: 'Relancer' }));

    expect(await screen.findByText('Extraction du texte en cours. Cette page se met à jour d’elle-même.')).toBeInTheDocument();
    expect(retried).toBe(true);
    expect(screen.getByText('Extraction relancée')).toBeInTheDocument();
  });

  it('describes a scanned document, and warns about a truncated text', async () => {
    const sent = server(view({}, {
      mediaType: PDF, filename: 'schema-cablage-salle-b.pdf', extractionStatus: 'SCANNED', pageCount: 4, sectionCount: 4, textTruncated: true,
    }));
    open(`/documents/${ID}`);
    const browser = userEvent.setup();

    expect(await screen.findByText(/^Document numérisé\s: le texte de ses pages numérisées n’a pas pu être extrait/)).toBeInTheDocument();
    expect(screen.getByText(/^Texte tronqué/)).toBeInTheDocument();
    expect(within(screen.getByRole('complementary', { name: 'Propriétés' })).getByText(/^PDF, 4 pages, 1,2\sMo$/)).toBeInTheDocument();

    await browser.click(screen.getAllByRole('button', { name: 'Ajouter une description' })[0]);
    const dialog = await screen.findByRole('dialog', { name: 'Ajouter une description' });
    await browser.type(within(dialog).getByLabelText('Description'), 'Baies B1 à B6 et leur câblage vers le cœur de réseau.');
    await browser.click(within(dialog).getByRole('button', { name: 'Enregistrer' }));

    expect(await screen.findByText('Description enregistrée')).toBeInTheDocument();
    expect(sent.PATCH).toEqual([{ description: 'Baies B1 à B6 et leur câblage vers le cœur de réseau.' }]);
    expect(screen.getByText('Baies B1 à B6 et leur câblage vers le cœur de réseau.')).toBeInTheDocument();
  });

  it('changes the title in place, then the category', async () => {
    const sent = server(view({}, { extractionStatus: 'INDEXED', sectionCount: 14, language: 'fr' }));
    open(`/documents/${ID}`);
    const browser = userEvent.setup();

    await browser.click(await screen.findByRole('button', { name: 'Modifier le titre' }));
    const title = screen.getByLabelText('Titre');
    await browser.clear(title);
    await browser.type(title, 'Plan de reprise 2026{Enter}');
    expect(await screen.findByRole('heading', { level: 1, name: 'Plan de reprise 2026' })).toBeInTheDocument();

    await browser.click(screen.getByRole('button', { name: /^Catégorie\s: Sans catégorie$/ }));
    await browser.click(await screen.findByRole('menuitemradio', { name: 'Runbook' }));
    await waitFor(() => expect(sent.PATCH).toEqual([{ title: 'Plan de reprise 2026' }, { category: 'RUNBOOK' }]));
    expect(await screen.findByRole('button', { name: /^Catégorie\s: Runbook$/ })).toBeInTheDocument();
  });

  it('moves the document to another folder', async () => {
    let moved: unknown;
    server(view(), {
      [`POST /api/v1/documents/${ID}/move`]: async (request) => {
        moved = await request.json();
        return json(view({ folder: { id: PROCEDURES, name: 'Procédures' } }));
      },
    });
    open(`/documents/${ID}`);
    const browser = userEvent.setup();

    await browser.click(await screen.findByRole('button', { name: /^Dossier\s: Sans dossier$/ }));
    const dialog = await screen.findByRole('dialog', { name: 'Déplacer le document' });
    await within(dialog).findByRole('option', { name: 'Procédures' });
    await browser.selectOptions(within(dialog).getByLabelText('Dossier de destination'), 'Procédures');
    await browser.click(within(dialog).getByRole('button', { name: 'Déplacer' }));

    expect(await screen.findByText('Document déplacé')).toBeInTheDocument();
    expect(moved).toEqual({ folderId: PROCEDURES });
    expect(screen.getByRole('button', { name: /^Dossier\s: Procédures$/ })).toBeInTheDocument();
  });

  it('deletes the document once confirmed, and goes back to its folder', async () => {
    let deleted = false;
    server(view(), {
      [`DELETE /api/v1/documents/${ID}`]: () => {
        deleted = true;
        return noContent();
      },
    });
    const router = open(`/documents/${ID}`);
    const browser = userEvent.setup();

    await browser.click(await screen.findByRole('button', { name: 'Autres actions' }));
    await browser.click(await screen.findByRole('menuitem', { name: 'Supprimer' }));
    const dialog = await screen.findByRole('dialog', { name: /^Supprimer «\sPlan reprise 2026\s»/ });
    expect(dialog).toHaveTextContent('Le document et son fichier seront supprimés définitivement.');
    await browser.click(within(dialog).getByRole('button', { name: 'Supprimer' }));

    await waitFor(() => expect(router.state.location.pathname).toBe('/spaces/infrastructure'));
    expect(router.state.location.search).toBe('?folder=none');
    expect(deleted).toBe(true);
    expect(await screen.findByText('Document supprimé')).toBeInTheDocument();
  });

  it('shows the same page for a document that does not exist or cannot be seen', async () => {
    server(view(), { [`GET /api/v1/documents/${ID}`]: () => json({ status: 404, code: 'not_found' }, 404) });
    open(`/documents/${ID}`);
    expect(await screen.findByRole('heading', { name: 'Cette page est introuvable' })).toBeInTheDocument();
  });
});

describe('document access', () => {
  it('restricts the document to some people, the maintainers and its owner always included', async () => {
    let restricted = false;
    let changed: unknown;
    const people = [
      { ...herve, reasons: ['chosen'] },
      { ...eli, reasons: ['maintainer'] },
      { ...christ, reasons: ['owner'] },
    ];
    server(view({ owner: { id: christ.id, displayName: christ.displayName } }), {
      [`GET /api/v1/documents/${ID}`]: () => json(view({ restricted, owner: { id: christ.id, displayName: christ.displayName } })),
      [`GET /api/v1/documents/${ID}/access`]: () => json({ restricted, spaceMembers: 4, people: restricted ? people : [] }),
      [`PUT /api/v1/documents/${ID}/access`]: async (request) => {
        changed = await request.json();
        restricted = true;
        return json({ restricted, spaceMembers: 4, people });
      },
      [`GET /api/v1/spaces/${SPACE_ID}/members`]: () => json([
        member(eli, 'MAINTAINER', true), member(christ, 'CONTRIBUTOR'), member(herve, 'READER'), member(amenanPerson, 'CONTRIBUTOR'),
      ]),
    });
    open(`/documents/${ID}`);
    const browser = userEvent.setup();

    await browser.click(await screen.findByRole('button', { name: 'Autres actions' }));
    await browser.click(await screen.findByRole('menuitem', { name: 'Gérer l’accès' }));
    const dialog = await screen.findByRole('dialog', { name: 'Accès au document' });
    expect(await within(dialog).findByRole('radio', { name: /Tous les membres de l’espace/ })).toBeChecked();
    expect(within(dialog).getByText('4 personnes')).toBeInTheDocument();
    const people0 = await within(dialog).findByRole('group', { name: 'Personnes qui voient le document' });
    expect(people0).toBeDisabled();

    await browser.click(within(dialog).getByRole('radio', { name: /Certaines personnes seulement/ }));
    expect(people0).toBeEnabled();
    const maintainer = within(people0).getByRole('checkbox', { name: /Eli Brou/ });
    const owner = within(people0).getByRole('checkbox', { name: /Christ Koffi/ });
    expect(maintainer).toBeChecked();
    expect(maintainer).toBeDisabled();
    expect(owner).toBeChecked();
    expect(owner).toBeDisabled();
    expect(within(people0).getByRole('checkbox', { name: /Amenan Souaga/ })).not.toBeChecked();
    await browser.click(within(people0).getByRole('checkbox', { name: /Hervé Coulibaly/ }));
    await browser.click(within(dialog).getByRole('button', { name: 'Enregistrer' }));

    expect(await screen.findByText('Accès mis à jour')).toBeInTheDocument();
    expect(changed).toEqual({ restricted: true, userIds: [herve.id] });
    const properties = screen.getByRole('complementary', { name: 'Propriétés' });
    expect(await within(properties).findByText(/^Accès restreint\s: seules ces personnes/)).toBeInTheDocument();
    expect(within(properties).getByText('Hervé Coulibaly')).toBeInTheDocument();
    expect(within(properties).getByText('Responsable du document')).toBeInTheDocument();
    expect(await within(properties).findByRole('button', { name: 'Modifier l’accès' })).toBeInTheDocument();
  });
});

describe('changes around a document', () => {
  it('asks the members again when someone chosen has left the space meanwhile', async () => {
    let left = false;
    const sent: unknown[] = [];
    server(view({ owner: { id: christ.id, displayName: christ.displayName } }), {
      [`PUT /api/v1/documents/${ID}/access`]: async (request) => {
        sent.push(await request.json());
        if (sent.length === 1) {
          // Hervé was removed from the space while the window was open.
          left = true;
          return json({ status: 400, code: 'invalid_request', errors: [{ field: 'userIds', code: 'not_space_member', params: {} }] }, 400);
        }
        return json({ restricted: true, spaceMembers: 2, people: [{ ...eli, reasons: ['maintainer'] }, { ...christ, reasons: ['owner'] }] });
      },
      [`GET /api/v1/spaces/${SPACE_ID}/members`]: () => json([
        member(eli, 'MAINTAINER', true), member(christ, 'CONTRIBUTOR'), ...(left ? [] : [member(herve, 'READER')]),
      ]),
    });
    open(`/documents/${ID}`);
    const browser = userEvent.setup();

    await browser.click(await screen.findByRole('button', { name: 'Autres actions' }));
    await browser.click(await screen.findByRole('menuitem', { name: 'Gérer l’accès' }));
    const dialog = await screen.findByRole('dialog', { name: 'Accès au document' });
    await browser.click(await within(dialog).findByRole('radio', { name: /Certaines personnes seulement/ }));
    await browser.click(await within(dialog).findByRole('checkbox', { name: /Hervé Coulibaly/ }));
    await browser.click(within(dialog).getByRole('button', { name: 'Enregistrer' }));

    expect(await within(dialog).findByRole('alert')).toHaveTextContent('Une des personnes choisies ne fait plus partie de l’espace.');
    await waitFor(() => expect(within(dialog).queryByRole('checkbox', { name: /Hervé Coulibaly/ })).not.toBeInTheDocument());
    await browser.click(within(dialog).getByRole('button', { name: 'Enregistrer' }));

    expect(await screen.findByText('Accès mis à jour')).toBeInTheDocument();
    expect(sent).toEqual([{ restricted: true, userIds: [herve.id] }, { restricted: true, userIds: [] }]);
  });

  it('asks the documents again when a member is removed from the space', async () => {
    fakeServer({
      'GET /api/v1/session': () => json({ setupRequired: false, organization, user: eli }),
      'GET /api/v1/spaces': () => json([space({ role: 'MAINTAINER' })]),
      'GET /api/v1/spaces/by-slug/infrastructure': () => json(space({ role: 'MAINTAINER' })),
      [`GET /api/v1/spaces/${SPACE_ID}/members`]: () => json([member(eli, 'MAINTAINER', true), member(herve, 'READER')]),
      [`DELETE /api/v1/spaces/${SPACE_ID}/members/${herve.id}`]: () => noContent(),
      [`GET /api/v1/spaces/${SPACE_ID}/documents`]: () => json(page([])),
      [`GET /api/v1/spaces/${SPACE_ID}/folders`]: () => json(folders),
    });
    // A document seen earlier, with its access: Hervé may have been one of its people.
    queryClient.setQueryData(keys.document(ID), view({ restricted: true }));
    queryClient.setQueryData(keys.documentAccess(ID), { restricted: true, spaceMembers: 2, people: [{ ...herve, reasons: ['chosen'] }] });
    open('/spaces/infrastructure/members');
    const browser = userEvent.setup();

    await browser.click(await screen.findByRole('button', { name: 'Retirer Hervé Coulibaly de l’espace' }));
    const dialog = await screen.findByRole('dialog', { name: /^Retirer Hervé Coulibaly de l’espace/ });
    await browser.click(within(dialog).getByRole('button', { name: 'Retirer' }));

    expect(await screen.findByText('Membre retiré de l’espace')).toBeInTheDocument();
    await waitFor(() => expect(queryClient.getQueryState(keys.documentAccess(ID))?.isInvalidated).toBe(true));
    expect(queryClient.getQueryState(keys.document(ID))?.isInvalidated).toBe(true);
  });

  it('stops asking about a document that no longer exists', async () => {
    timing.refresh = 20;
    let asked = 0;
    let deleted = false;
    server(view(), {
      [`GET /api/v1/documents/${ID}`]: () => {
        asked += 1;
        return deleted ? json({ status: 404, code: 'not_found' }, 404) : json(view());
      },
    });
    open(`/documents/${ID}`);

    expect(await screen.findByText('Extraction du texte en cours. Cette page se met à jour d’elle-même.')).toBeInTheDocument();
    deleted = true;
    expect(await screen.findByRole('heading', { name: 'Cette page est introuvable' })).toBeInTheDocument();
    const count = asked;
    await new Promise((resolve) => setTimeout(resolve, 100));
    expect(asked).toBe(count);
  });

  it('tells screen readers when the extraction ends', async () => {
    timing.refresh = 20;
    let done = false;
    server(view(), {
      [`GET /api/v1/documents/${ID}`]: () => json(done ? view({}, { extractionStatus: 'INDEXED', sectionCount: 14, language: 'fr' }) : view()),
    });
    open(`/documents/${ID}`);
    await screen.findByText('Extraction du texte en cours. Cette page se met à jour d’elle-même.');
    const status = screen.getAllByRole('status').find((element) => element.classList.contains('sr-only')) as HTMLElement;
    expect(status).toHaveTextContent('');

    done = true;
    await waitFor(() => expect(status).toHaveTextContent(/^Extraction du texte terminée\. Indexé\s: 14 sections$/));
  });
});

describe('around the documents', () => {
  it('shows on the home page the documents of each space and the imports to handle', async () => {
    fakeServer({
      'GET /api/v1/session': () => json({ setupRequired: false, organization, user: eli }),
      'GET /api/v1/spaces': () => json([space({ documentCount: 3 })]),
      'GET /api/v1/imports/attention': () => json([
        { id: 'd1', title: 'Procédure VPN', space: { id: SPACE_ID, name: 'Infrastructure', slug: 'infrastructure', color: '#13705E' }, extractionStatus: 'FAILED', extractionError: 'encrypted' },
        { id: 'd2', title: 'Runbook Redis', space: { id: SPACE_ID, name: 'Infrastructure', slug: 'infrastructure', color: '#13705E' }, extractionStatus: 'PENDING', extractionError: null },
      ]),
    });
    open('/');

    expect(await screen.findByText('3 documents')).toBeInTheDocument();
    const todo = await screen.findByRole('region', { name: 'À traiter' });
    const failed = await within(todo).findByRole('list', { name: /Import en échec/ });
    expect(within(failed).getByRole('link', { name: /Procédure VPN/ })).toHaveAttribute('href', '/documents/d1');
    expect(within(failed).getByText(/le fichier est protégé par un mot de passe/)).toBeInTheDocument();
    const pending = within(todo).getByRole('list', { name: /Indexation en cours/ });
    expect(within(pending).getByRole('link', { name: /Runbook Redis/ })).toHaveAttribute('href', '/documents/d2');
    expect(screen.getByText('Importer des documents')).toBeInTheDocument();
  });

  it('counts the documents of each space in the list of spaces', async () => {
    fakeServer({
      'GET /api/v1/session': () => json({ setupRequired: false, organization, user: eli }),
      'GET /api/v1/spaces': () => json([space({ documentCount: 12 })]),
    });
    open('/spaces');
    const table = await screen.findByRole('table');
    const row = (await within(table).findByRole('link', { name: 'Infrastructure' })).closest('tr') as HTMLElement;
    expect(screen.getByRole('columnheader', { name: 'Documents' })).toBeInTheDocument();
    expect(within(row).getByText('12')).toBeInTheDocument();
  });

  it('follows the document of a list item to its page', async () => {
    server(view({}, { extractionStatus: 'INDEXED', sectionCount: 14, language: 'fr' }), {
      [`GET /api/v1/spaces/${SPACE_ID}/documents`]: () => json(page([item({ id: ID })])),
      [`GET /api/v1/spaces/${SPACE_ID}/documents/summary`]: () => json({ total: 1, inProgress: 0, scanned: 0, failed: 0, folders: [{ folderId: null, count: 1 }] }),
      [`GET /api/v1/documents/${ID}/versions/1/rendering`]: () => json({
        number: 1, renderer: 1, part: 1, partCount: 1, truncated: false, outline: [], html: '<section id="h-0"><p>Texte.</p></section>',
      }),
    });
    const router = open('/spaces/infrastructure');
    const browser = userEvent.setup();
    await browser.click(await screen.findByRole('link', { name: 'Plan reprise 2026' }));
    await waitFor(() => expect(router.state.location.pathname).toBe(`/documents/${ID}`));
    expect(await screen.findByRole('heading', { level: 1, name: 'Plan reprise 2026' })).toBeInTheDocument();
    expect(screen.getByText(/^Indexé$/)).toBeInTheDocument();
    expect(within(screen.getByRole('complementary', { name: 'Propriétés' })).getByText('Français')).toBeInTheDocument();
    expect(await screen.findByText('Texte.')).toBeInTheDocument();
  });
});
