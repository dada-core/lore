import { QueryClientProvider } from '@tanstack/react-query';
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter } from 'react-router';
import { RouterProvider } from 'react-router/dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { queryClient } from '../api/queryClient';
import { routes } from '../router';
import { eli, file, folders, limits, PDF, page, PROCEDURES, space, SPACE_ID, item, view, member, herve, christ } from '../test/documents';
import { fakeServer, json, noContent, organization } from '../test/server';
import { fakeUploads } from '../test/xhr';
import { ToastProvider } from '../ui/Toast';
import { timing } from './documents';

type Routes = Parameters<typeof fakeServer>[0];

/** A contributor of Infrastructure, its library behind the window. */
function server(routes: Routes = {}) {
  return fakeServer({
    'GET /api/v1/session': () => json({ setupRequired: false, organization, user: eli }),
    'GET /api/v1/spaces': () => json([space()]),
    'GET /api/v1/spaces/by-slug/infrastructure': () => json(space()),
    [`GET /api/v1/spaces/${SPACE_ID}/folders`]: () => json(folders),
    [`GET /api/v1/spaces/${SPACE_ID}/documents/summary`]: () => json({ total: 0, inProgress: 0, scanned: 0, failed: 0, folders: [] }),
    [`GET /api/v1/spaces/${SPACE_ID}/documents`]: () => json(page([])),
    'GET /api/v1/imports/limits': () => json(limits),
    'GET /api/v1/imports/attention': () => json([]),
    ...routes,
  });
}

/** Opens the address; with earlier ones, "Back" leads to them. */
function open(path: string, before: string[] = []) {
  const router = createMemoryRouter(routes, { initialEntries: [...before, path], initialIndex: before.length });
  render(
    <QueryClientProvider client={queryClient}>
      <ToastProvider>
        <RouterProvider router={router} />
      </ToastProvider>
    </QueryClientProvider>,
  );
  return router;
}

/** The import window, once the limits are known. */
async function importWindow() {
  const dialog = await screen.findByRole('dialog', { name: 'Importer des documents' });
  await within(dialog).findByText(/Mo au maximum par fichier/);
  return dialog;
}

/** The list of the files dropped in the window. */
function fileList(dialog: HTMLElement) {
  return within(within(dialog).getByRole('list'));
}

/** What screen readers are told about the files, oldest first. */
function news(dialog: HTMLElement): string[] {
  return within(within(dialog).getByRole('log', { name: 'Ce qui arrive aux fichiers' }))
    .queryAllByText(/./).map((line) => line.textContent ?? '');
}

const asking = /^Interrompre l’envoi/;
const following = timing.follow;

beforeEach(() => {
  window.scrollTo = () => {};
});

afterEach(() => {
  // Unmounted first: a screen still shown would ask the real network again once the cache is cleared.
  cleanup();
  timing.follow = following;
  queryClient.clear();
  vi.unstubAllGlobals();
});

describe('import window', () => {
  it('checks the files before sending them, and sends the others with their destination', async () => {
    document.cookie = 'XSRF-TOKEN=jeton-1';
    server({ 'GET /api/v1/imports/limits': () => json({ ...limits, maxMegabytes: 1, maxBytes: 1024 * 1024 }) });
    const uploads = fakeUploads();
    open('/spaces/infrastructure?open=import');
    const browser = userEvent.setup({ applyAccept: false });
    const dialog = await importWindow();

    await within(dialog).findByRole('option', { name: 'Procédures' });
    await browser.selectOptions(within(dialog).getByLabelText('Dossier'), 'Procédures');
    await browser.selectOptions(within(dialog).getByLabelText('Statut à l’import'), 'PUBLISHED');
    await browser.selectOptions(within(dialog).getByLabelText('Catégorie'), 'RUNBOOK');
    await browser.upload(within(dialog).getByLabelText('Fichiers à importer'), [
      file('procedure-vpn.doc'),
      file('schema-salle-b.png'),
      file('notes-vides.md', 0),
      file('export-journaux.pdf', 3 * 1024 * 1024),
      file('runbook-redis.md'),
    ]);

    expect(within(dialog).getByText('5 fichiers déposés')).toBeInTheDocument();
    const list = fileList(dialog);
    expect(list.getByText(/les fichiers Word \.doc ne sont pas acceptés\. Enregistrez-le au format DOCX/)).toBeInTheDocument();
    expect(list.getByText(/Formats acceptés\s: \.pdf, \.docx, \.md, \.markdown, \.txt\./)).toBeInTheDocument();
    expect(list.getByText(/le fichier est vide/)).toBeInTheDocument();
    expect(list.getByText(/le fichier fait 3\sMo, la limite est de 1\sMo/)).toBeInTheDocument();
    // Screen readers hear each refusal, with the name of the file.
    expect(news(dialog)).toEqual([
      expect.stringMatching(/^procedure-vpn\.doc\s: Non importé\s: les fichiers Word \.doc/),
      expect.stringMatching(/^schema-salle-b\.png\s: Non importé\s: ce format/),
      expect.stringMatching(/^notes-vides\.md\s: Non importé\s: le fichier est vide/),
      expect.stringMatching(/^export-journaux\.pdf\s: Non importé\s: le fichier fait 3/),
    ]);
    // Only the accepted file leaves, with the destination chosen and the CSRF token of the cookie.
    expect(uploads.sent).toHaveLength(1);
    const [sent] = uploads.sent;
    expect(sent.method).toBe('POST');
    expect(sent.url).toBe(`/api/v1/spaces/${SPACE_ID}/documents`);
    expect(sent.headers['X-XSRF-TOKEN']).toBe('jeton-1');
    expect(sent.filename).toBe('runbook-redis.md');
    expect(sent.field('folderId')).toEqual([PROCEDURES]);
    expect(sent.field('status')).toEqual(['PUBLISHED']);
    expect(sent.field('category')).toEqual(['RUNBOOK']);
    expect(sent.field('restricted')).toEqual([]);
  });

  it('restricts the documents to the people chosen, the maintainers and oneself included', async () => {
    server({
      [`GET /api/v1/spaces/${SPACE_ID}/members`]: () => json([
        member(eli, 'CONTRIBUTOR', true), member(christ, 'MAINTAINER'), member(herve, 'READER'),
      ]),
    });
    const uploads = fakeUploads();
    open('/spaces/infrastructure?open=import');
    const browser = userEvent.setup();
    const dialog = await importWindow();

    await browser.click(within(dialog).getByLabelText('Restreindre l’accès à certaines personnes'));
    const people = await within(dialog).findByRole('group', { name: 'Personnes qui verront ces documents' });
    expect(await within(people).findByRole('checkbox', { name: /Eli Brou/ })).toBeChecked();
    expect(within(people).getByRole('checkbox', { name: /Eli Brou/ })).toBeDisabled();
    expect(within(people).getByRole('checkbox', { name: /Christ Koffi/ })).toBeChecked();
    expect(within(people).getByRole('checkbox', { name: /Christ Koffi/ })).toBeDisabled();
    await browser.click(within(people).getByRole('checkbox', { name: /Hervé Coulibaly/ }));
    await browser.upload(within(dialog).getByLabelText('Fichiers à importer'), [file('rotation-tls.md')]);

    expect(uploads.sent[0].field('restricted')).toEqual(['true']);
    expect(uploads.sent[0].field('userIds')).toEqual([herve.id]);
  });

  it('sends five files at a time', async () => {
    server();
    const uploads = fakeUploads();
    open('/spaces/infrastructure?open=import');
    const browser = userEvent.setup();
    const dialog = await importWindow();

    await browser.upload(within(dialog).getByLabelText('Fichiers à importer'), Array.from({ length: 7 }, (_, index) => file(`note-${index + 1}.md`)));

    expect(uploads.sent.map((request) => request.filename)).toEqual(['note-1.md', 'note-2.md', 'note-3.md', 'note-4.md', 'note-5.md']);
    expect(within(dialog).getAllByText('En attente')).toHaveLength(2);
    await act(async () => uploads.sent[1].respond(201, view({ id: 'd2' })));
    expect(uploads.sent.map((request) => request.filename)).toEqual(['note-1.md', 'note-2.md', 'note-3.md', 'note-4.md', 'note-5.md', 'note-6.md']);
    expect(uploads.pending).toHaveLength(5);
    expect(within(dialog).getAllByText('En attente')).toHaveLength(1);
  });

  it('stops following the documents of a space out of reach', async () => {
    timing.follow = 20;
    let asked = 0;
    server({
      [`GET /api/v1/spaces/${SPACE_ID}/documents`]: (request) => {
        if (new URL(request.url).searchParams.getAll('ids').length === 0) return json(page([]));
        asked += 1;
        return json({ status: 404, code: 'not_found' }, 404);
      },
    });
    const uploads = fakeUploads();
    open('/spaces/infrastructure?open=import');
    const browser = userEvent.setup();
    const dialog = await importWindow();

    await browser.upload(within(dialog).getByLabelText('Fichiers à importer'), [file('plan-reprise-2026.docx')]);
    await act(async () => uploads.sent[0].respond(201, view({ id: 'd1' })));

    expect(await fileList(dialog).findByText('Ce document a été supprimé entre-temps.')).toBeInTheDocument();
    const after = asked;
    await act(async () => new Promise((resolve) => setTimeout(resolve, 150)));
    expect(asked).toBe(after);
  });

  it('shows the progress, then follows the extraction every two seconds until it ends', async () => {
    timing.follow = 20;
    const asked: string[][] = [];
    const extracted = new Map<string, unknown>();
    server({
      [`GET /api/v1/spaces/${SPACE_ID}/documents`]: (request) => {
        const ids = new URL(request.url).searchParams.getAll('ids');
        if (ids.length > 0) asked.push(ids);
        return json(page(ids.map((id) => extracted.get(id) ?? item({ id, extractionStatus: 'PENDING' }))));
      },
    });
    const uploads = fakeUploads();
    open('/spaces/infrastructure?open=import');
    const browser = userEvent.setup();
    const dialog = await importWindow();

    await browser.upload(within(dialog).getByLabelText('Fichiers à importer'), [file('plan-reprise-2026.docx'), file('guide-baie.pdf')]);
    act(() => uploads.sent[0].progress(42, 100));
    expect(within(dialog).getByText(/^Envoi… 42\s%$/)).toBeInTheDocument();

    await act(async () => {
      uploads.sent[0].respond(201, view({ id: 'd1' }));
      uploads.sent[1].respond(201, view({ id: 'd2', title: 'Guide baie' }, { mediaType: PDF }));
    });
    expect(fileList(dialog).getAllByText('Extraction du texte en cours')).toHaveLength(2);
    await waitFor(() => expect(asked.length).toBeGreaterThan(0));
    expect(asked[0]).toEqual(['d1', 'd2']);

    extracted.set('d1', item({ id: 'd1', extractionStatus: 'INDEXED', sectionCount: 14 }));
    extracted.set('d2', item({ id: 'd2', mediaType: PDF, extractionStatus: 'INDEXED', pageCount: 312, sectionCount: 312 }));
    expect(await fileList(dialog).findByText(/^Indexé\s: 14 sections$/)).toBeInTheDocument();
    expect(await fileList(dialog).findByText(/^Indexé\s: 312 pages$/)).toBeInTheDocument();
    // The end of each extraction is told, once.
    expect(news(dialog).slice(-2)).toEqual([
      expect.stringMatching(/^plan-reprise-2026\.docx\s: Indexé\s: 14 sections$/),
      expect.stringMatching(/^guide-baie\.pdf\s: Indexé\s: 312 pages$/),
    ]);
    // Nothing is in progress any more: the window stops asking.
    const count = asked.length;
    await new Promise((resolve) => setTimeout(resolve, 80));
    expect(asked).toHaveLength(count);
  });

  it('says what to do after each outcome of the extraction, and removes a duplicate', async () => {
    const retried: string[] = [];
    const deleted: string[] = [];
    server({
      'POST /api/v1/documents/d2/extraction/retry': () => {
        retried.push('d2');
        return json(view({ id: 'd2' }));
      },
      'DELETE /api/v1/documents/d3': () => {
        deleted.push('d3');
        return noContent();
      },
    });
    const uploads = fakeUploads();
    open('/spaces/infrastructure?open=import');
    const browser = userEvent.setup();
    const dialog = await importWindow();

    await browser.upload(within(dialog).getByLabelText('Fichiers à importer'), [
      file('procedure-vpn.docx'), file('runbook-redis.md'), file('plan-reprise-copie.docx'), file('schema-cablage.pdf'),
    ]);
    await act(async () => uploads.sent[0].respond(201, view({ id: 'd1' }, { extractionStatus: 'FAILED', extractionError: 'encrypted' })));
    await act(async () => uploads.sent[1].respond(201, view({ id: 'd2' }, { extractionStatus: 'FAILED', extractionError: 'timeout' })));
    await act(async () => uploads.sent[2].respond(201, view(
      { id: 'd3', duplicateOf: { id: 'd0', title: 'Plan reprise 2026' } },
      { extractionStatus: 'INDEXED', sectionCount: 9 },
    )));
    await act(async () => uploads.sent[3].respond(201, view({ id: 'd4' }, { mediaType: PDF, extractionStatus: 'SCANNED', pageCount: 4 })));

    // A protected file: what to do, without "Retry".
    const list = fileList(dialog);
    expect(list.getByText(/le fichier est protégé par un mot de passe\. Retirez la protection, puis importez le fichier à nouveau\./)).toBeInTheDocument();
    expect(list.getAllByRole('button', { name: /^Relancer/ })).toHaveLength(1);
    expect(list.getByText(/l’extraction a pris plus de temps que la limite fixée/)).toBeInTheDocument();
    expect(list.getByText(/ses pages numérisées ne sont pas recherchables/)).toBeInTheDocument();
    expect(news(dialog)).toContainEqual(expect.stringMatching(/^plan-reprise-copie\.docx\s: Indexé\s: 9 sections Ce fichier est identique/));

    // Each button names its file: the list holds several of them.
    await browser.click(list.getByRole('button', { name: 'Relancer l’extraction du fichier runbook-redis.md' }));
    await waitFor(() => expect(retried).toEqual(['d2']));
    expect(await list.findByText('Extraction du texte en cours')).toBeInTheDocument();

    expect(list.getByText(/Ce fichier est identique à « Plan reprise 2026 », déjà dans l’espace\./)).toBeInTheDocument();
    await browser.click(list.getByRole('button', { name: /^Supprimer ce doublon\s: plan-reprise-copie\.docx$/ }));
    expect(await list.findByText('Doublon supprimé.')).toBeInTheDocument();
    expect(deleted).toEqual(['d3']);
  });

  it('sends again an interrupted file, and reports a refusal of the server', async () => {
    server();
    const uploads = fakeUploads();
    open('/spaces/infrastructure?open=import');
    const browser = userEvent.setup();
    const dialog = await importWindow();

    await browser.upload(within(dialog).getByLabelText('Fichiers à importer'), [file('runbook-redis.md'), file('faux.pdf')]);
    await act(async () => uploads.sent[0].fail());
    await act(async () => uploads.sent[1].respond(400, {
      status: 400, code: 'invalid_request', errors: [{ field: 'file', code: 'content_mismatch', params: {} }],
    }));
    const list = fileList(dialog);
    expect(list.getByText('Envoi interrompu.')).toBeInTheDocument();
    expect(list.getByText(/son contenu ne correspond pas à son extension/)).toBeInTheDocument();

    await browser.click(list.getByRole('button', { name: 'Réessayer l’envoi du fichier runbook-redis.md' }));
    expect(uploads.sent).toHaveLength(3);
    expect(uploads.sent[2].filename).toBe('runbook-redis.md');
    expect(list.queryByText('Envoi interrompu.')).not.toBeInTheDocument();
    await act(async () => uploads.sent[2].respond(413, { status: 413, code: 'file_too_large', maxMegabytes: 50 }));
    expect(list.getByText(/la limite est de 50\sMo/)).toBeInTheDocument();
  });

  it('gives the limit of the organization when a size refusal does not give one', async () => {
    server();
    const uploads = fakeUploads();
    open('/spaces/infrastructure?open=import');
    const browser = userEvent.setup();
    const dialog = await importWindow();

    await browser.upload(within(dialog).getByLabelText('Fichiers à importer'), [file('runbook-redis.md')]);
    await act(async () => uploads.sent[0].respond(413, { status: 413, code: 'file_too_large' }));

    expect(fileList(dialog).getByText(/la limite est de 50\sMo/)).toBeInTheDocument();
  });

  it('gets a new CSRF token and sends the file again once', async () => {
    document.cookie = 'XSRF-TOKEN=perime';
    let sessions = 0;
    server({
      'GET /api/v1/session': () => {
        sessions += 1;
        if (sessions > 1) document.cookie = 'XSRF-TOKEN=nouveau';
        return json({ setupRequired: false, organization, user: eli });
      },
    });
    const uploads = fakeUploads();
    open('/spaces/infrastructure?open=import');
    const browser = userEvent.setup();
    const dialog = await importWindow();

    await browser.upload(within(dialog).getByLabelText('Fichiers à importer'), [file('runbook-redis.md')]);
    expect(uploads.sent[0].headers['X-XSRF-TOKEN']).toBe('perime');
    await act(async () => uploads.sent[0].respond(403, { status: 403, code: 'csrf' }));
    await waitFor(() => expect(uploads.sent).toHaveLength(2));
    expect(sessions).toBe(2);
    expect(uploads.sent[1].headers['X-XSRF-TOKEN']).toBe('nouveau');
    expect(uploads.sent[1].filename).toBe('runbook-redis.md');
  });

  it('sends the file with the other CSRF cookie when one is left from before a change of the public address', async () => {
    // The public address went from https to http: the browser still holds the __Host- cookie, until it closes.
    const cookies = vi.spyOn(document, 'cookie', 'get').mockReturnValue('__Host-XSRF-TOKEN=perime; XSRF-TOKEN=valide');
    try {
      server();
      const uploads = fakeUploads();
      open('/spaces/infrastructure?open=import');
      const browser = userEvent.setup();
      const dialog = await importWindow();

      await browser.upload(within(dialog).getByLabelText('Fichiers à importer'), [file('runbook-redis.md')]);
      expect(uploads.sent[0].headers['X-XSRF-TOKEN']).toBe('perime');
      await act(async () => uploads.sent[0].respond(403, { status: 403, code: 'csrf' }));
      await waitFor(() => expect(uploads.sent).toHaveLength(2));
      expect(uploads.sent[1].headers['X-XSRF-TOKEN']).toBe('perime');
      await act(async () => uploads.sent[1].respond(403, { status: 403, code: 'csrf' }));
      await waitFor(() => expect(uploads.sent).toHaveLength(3));
      expect(uploads.sent[2].headers['X-XSRF-TOKEN']).toBe('valide');
      expect(uploads.sent[2].filename).toBe('runbook-redis.md');
    } finally {
      cookies.mockRestore();
    }
  });

  it('asks before closing while files are being sent', async () => {
    server();
    const uploads = fakeUploads();
    const router = open('/spaces/infrastructure?open=import');
    const browser = userEvent.setup();
    const dialog = await importWindow();

    await browser.upload(within(dialog).getByLabelText('Fichiers à importer'), [file('a.md'), file('b.md'), file('c.md')]);
    // c.md has entirely left: the server imports it whatever happens, it is not counted.
    act(() => uploads.sent[2].progress(100, 100));
    await browser.click(within(dialog).getByRole('button', { name: 'Terminé' }));
    const confirm = await screen.findByRole('dialog', { name: /^Interrompre l’envoi/ });
    expect(confirm).toHaveTextContent(/2 fichiers ne sont pas encore entièrement envoyés : ils ne seront pas importés\. Les fichiers déjà reçus par le serveur seront importés\./);
    await browser.click(within(confirm).getByRole('button', { name: 'Continuer l’envoi' }));
    await waitFor(() => expect(screen.queryByRole('dialog', { name: /^Interrompre l’envoi/ })).not.toBeInTheDocument());
    expect(uploads.sent.some((request) => request.aborted)).toBe(false);

    await browser.click(within(dialog).getByRole('button', { name: 'Fermer' }));
    const again = await screen.findByRole('dialog', { name: /^Interrompre l’envoi/ });
    await browser.click(within(again).getByRole('button', { name: 'Fermer et interrompre' }));

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(uploads.sent.every((request) => request.aborted)).toBe(true);
    expect(router.state.location.pathname).toBe('/spaces/infrastructure');
    expect(router.state.location.search).toBe('');
  });

  it('closes at once when nothing is being sent', async () => {
    server();
    const uploads = fakeUploads();
    open('/spaces/infrastructure?open=import');
    const browser = userEvent.setup();
    const dialog = await importWindow();

    await browser.upload(within(dialog).getByLabelText('Fichiers à importer'), [file('a.md')]);
    await act(async () => uploads.sent[0].respond(201, view({ id: 'd1' }, { extractionStatus: 'INDEXED', sectionCount: 2 })));
    await browser.click(within(dialog).getByRole('button', { name: 'Terminé' }));

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
  });

  it('asks first for the space from the home page, among those where one contributes', async () => {
    server({
      'GET /api/v1/spaces': () => json([
        space({ documentCount: 3 }),
        space({ id: 'b1', name: 'Sécurité', slug: 'securite', role: 'READER' }),
        space({ id: 'c1', name: 'Réseau', slug: 'reseau', role: 'MAINTAINER' }),
      ]),
    });
    fakeUploads();
    open('/');
    const browser = userEvent.setup();

    await browser.click(await screen.findByRole('button', { name: 'Importer' }));
    const dialog = await screen.findByRole('dialog', { name: 'Importer des documents' });
    const choice = within(dialog).getByLabelText('Espace');
    expect(within(choice).getAllByRole('option').map((option) => option.textContent)).toEqual(['Choisir un espace', 'Infrastructure', 'Réseau']);
    expect(within(dialog).getByText('Choisissez d’abord l’espace où importer.')).toBeInTheDocument();
    expect(within(dialog).queryByRole('button', { name: 'choisissez-les sur votre ordinateur' })).not.toBeInTheDocument();

    await browser.selectOptions(choice, 'Infrastructure');
    expect(within(dialog).getByRole('button', { name: 'choisissez-les sur votre ordinateur' })).toBeInTheDocument();
    expect(await within(dialog).findByRole('option', { name: 'Procédures' })).toBeInTheDocument();
  });

  it('opens with the I key where one may import', async () => {
    server();
    open('/spaces/infrastructure');
    const browser = userEvent.setup();
    await screen.findByRole('heading', { name: 'Infrastructure' });
    await browser.keyboard('i');
    expect(await screen.findByRole('dialog', { name: 'Importer des documents' })).toBeInTheDocument();
  });

  it('offers no import to a reader', async () => {
    server({
      'GET /api/v1/spaces': () => json([space({ role: 'READER' })]),
      'GET /api/v1/spaces/by-slug/infrastructure': () => json(space({ role: 'READER' })),
    });
    const router = open('/spaces/infrastructure');
    const browser = userEvent.setup();
    expect(await screen.findByRole('heading', { name: 'Infrastructure' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Importer/ })).not.toBeInTheDocument();
    await browser.keyboard('i');
    // Opening goes through a change of address: given the time to happen, it does not.
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(router.state.location.search).toBe('');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });
});

describe('import window, while files are being sent', () => {
  it('asks before going back, and goes back only once asked', async () => {
    server();
    const uploads = fakeUploads();
    const router = open('/spaces/infrastructure?open=import', ['/spaces/infrastructure']);
    const browser = userEvent.setup();
    const dialog = await importWindow();

    await browser.upload(within(dialog).getByLabelText('Fichiers à importer'), [file('a.md'), file('b.md')]);
    await act(() => router.navigate(-1));
    const confirm = await screen.findByRole('dialog', { name: asking });
    expect(router.state.location.search).toBe('?open=import');
    expect(uploads.sent.some((request) => request.aborted)).toBe(false);

    await browser.click(within(confirm).getByRole('button', { name: 'Continuer l’envoi' }));
    await waitFor(() => expect(screen.queryByRole('dialog', { name: asking })).not.toBeInTheDocument());
    expect(screen.getByRole('dialog', { name: 'Importer des documents' })).toBeInTheDocument();
    expect(router.state.location.search).toBe('?open=import');

    await act(() => router.navigate(-1));
    await browser.click(within(await screen.findByRole('dialog', { name: asking })).getByRole('button', { name: 'Fermer et interrompre' }));
    await waitFor(() => expect(router.state.location.search).toBe(''));
    expect(router.state.location.pathname).toBe('/spaces/infrastructure');
    // The window goes in the render that follows the change of address.
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(uploads.sent.every((request) => request.aborted)).toBe(true);
  });

  it('goes back without asking once every file has left', async () => {
    server();
    const uploads = fakeUploads();
    const router = open('/spaces/infrastructure?open=import', ['/spaces/infrastructure']);
    const browser = userEvent.setup();
    const dialog = await importWindow();

    await browser.upload(within(dialog).getByLabelText('Fichiers à importer'), [file('a.md')]);
    await act(async () => uploads.sent[0].respond(201, view({ id: 'd1' })));
    await act(() => router.navigate(-1));
    await waitFor(() => expect(router.state.location.search).toBe(''));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('forgets the question once nothing is being sent, and does not ask again by itself', async () => {
    server();
    const uploads = fakeUploads();
    open('/spaces/infrastructure?open=import');
    const browser = userEvent.setup();
    const dialog = await importWindow();

    await browser.upload(within(dialog).getByLabelText('Fichiers à importer'), [file('a.md'), file('b.md')]);
    await browser.click(within(dialog).getByRole('button', { name: 'Terminé' }));
    expect(await screen.findByRole('dialog', { name: asking })).toHaveTextContent(/^.*2 fichiers/);

    await act(async () => uploads.sent[0].respond(201, view({ id: 'd1' })));
    expect(screen.getByRole('dialog', { name: asking })).toHaveTextContent(/1 fichier n’est pas encore entièrement envoyé/);
    await act(async () => uploads.sent[1].fail());
    await waitFor(() => expect(screen.queryByRole('dialog', { name: asking })).not.toBeInTheDocument());

    // Sending again later does not bring the old question back.
    await browser.click(fileList(dialog).getByRole('button', { name: 'Réessayer l’envoi du fichier b.md' }));
    expect(uploads.sent).toHaveLength(3);
    await new Promise((resolve) => setTimeout(resolve, 30));
    expect(screen.queryByRole('dialog', { name: asking })).not.toBeInTheDocument();
  });

  it('takes an answer that is not a document for an interrupted sending, and frees its place', async () => {
    server();
    const uploads = fakeUploads();
    open('/spaces/infrastructure?open=import');
    const browser = userEvent.setup();
    const dialog = await importWindow();

    await browser.upload(within(dialog).getByLabelText('Fichiers à importer'), Array.from({ length: 6 }, (_, index) => file(`note-${index + 1}.md`)));
    expect(uploads.sent).toHaveLength(5);
    await act(async () => uploads.sent[0].respondText(200, '<!doctype html><title>Connexion</title>'));

    expect(fileList(dialog).getByText('Envoi interrompu.')).toBeInTheDocument();
    expect(fileList(dialog).getByRole('button', { name: 'Réessayer l’envoi du fichier note-1.md' })).toBeInTheDocument();
    expect(uploads.sent).toHaveLength(6);
    expect(uploads.sent[5].filename).toBe('note-6.md');
  });
});

describe('Escape', () => {
  it('closes a window through its owner', async () => {
    server();
    open('/spaces/infrastructure');
    const browser = userEvent.setup();
    await browser.click(await screen.findByRole('button', { name: 'Nouveau dossier' }));
    const dialog = await screen.findByRole('dialog', { name: 'Nouveau dossier' });

    const escape = new Event('cancel', { cancelable: true });
    fireEvent(dialog, escape);

    expect(escape.defaultPrevented).toBe(true);
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
  });

  it('keeps the import window open while files are being sent, and asks', async () => {
    server();
    fakeUploads();
    open('/spaces/infrastructure?open=import');
    const browser = userEvent.setup();
    const dialog = await importWindow();
    await browser.upload(within(dialog).getByLabelText('Fichiers à importer'), [file('a.md')]);

    fireEvent(dialog, new Event('cancel', { cancelable: true }));

    expect(await screen.findByRole('dialog', { name: asking })).toBeInTheDocument();
    expect(dialog).toHaveAttribute('open');
  });

  it('opens the import window again under the question when the browser closes it anyway', async () => {
    server();
    fakeUploads();
    open('/spaces/infrastructure?open=import');
    const browser = userEvent.setup();
    const dialog = await importWindow();
    await browser.upload(within(dialog).getByLabelText('Fichiers à importer'), [file('a.md')]);
    const shown: HTMLDialogElement[] = [];
    const showModal = HTMLDialogElement.prototype.showModal;
    vi.spyOn(HTMLDialogElement.prototype, 'showModal').mockImplementation(function (this: HTMLDialogElement) {
      shown.push(this);
      showModal.call(this);
    });

    // After a few Escapes in a row, Chrome no longer lets the window be held back, and closes it.
    act(() => {
      fireEvent(dialog, new Event('cancel', { cancelable: false }));
      (dialog as HTMLDialogElement).close();
    });

    const question = await screen.findByRole('dialog', { name: asking });
    expect(dialog).toHaveAttribute('open');
    // The import window first, the question last: the question is the one on top.
    expect(shown[0]).toBe(dialog);
    expect(shown.at(-1)).toBe(question);
    await browser.click(within(question).getByRole('button', { name: 'Continuer l’envoi' }));
    await waitFor(() => expect(screen.queryByRole('dialog', { name: asking })).not.toBeInTheDocument());
    expect(dialog).toHaveAttribute('open');
    vi.restoreAllMocks();
  });
});

