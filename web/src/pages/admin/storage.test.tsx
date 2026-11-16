import { QueryClientProvider } from '@tanstack/react-query';
import { cleanup, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter } from 'react-router';
import { RouterProvider } from 'react-router/dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { queryClient } from '../../api/queryClient';
import { routes } from '../../router';
import { amenan, fakeServer, json, organization } from '../../test/server';
import { ToastProvider } from '../../ui/Toast';

const storage = {
  usedBytes: 2_576_980_378, freeBytes: 412_316_860_416, documents: 128, inProgress: 2, scanned: 3, failed: 1,
  maxUploadMegabytes: 50, maxUploadCeilingMegabytes: 1024, defaultMaxUploadMegabytes: 50,
  extensions: ['pdf', 'docx', 'md', 'markdown', 'txt'],
  extraction: { active: true, workers: 2, problem: null as string | null },
  filesPath: '/var/lib/lore/files',
};

/** Administration › Storage and import with a fake server; returns the bodies of the PATCH requests. */
function openStorage(view = storage) {
  const patches: unknown[] = [];
  let current = { ...view };
  fakeServer({
    'GET /api/v1/session': () => json({ setupRequired: false, organization, user: amenan }),
    'GET /api/v1/admin/storage': () => json(current),
    'PATCH /api/v1/admin/storage': async (request) => {
      const change = (await request.json()) as { maxUploadMegabytes: number };
      patches.push(change);
      if (change.maxUploadMegabytes > current.maxUploadCeilingMegabytes) {
        return json({
          status: 400, code: 'invalid_request',
          errors: [{ field: 'maxUploadMegabytes', code: 'out_of_range', params: { min: 1, max: current.maxUploadCeilingMegabytes } }],
        }, 400);
      }
      current = { ...current, ...change };
      return json(current);
    },
  });
  render(
    <QueryClientProvider client={queryClient}>
      <ToastProvider>
        <RouterProvider router={createMemoryRouter(routes, { initialEntries: ['/admin/storage'] })} />
      </ToastProvider>
    </QueryClientProvider>,
  );
  return patches;
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

describe('Storage and import', () => {
  it('shows what is stored, the extraction and where the files are', async () => {
    openStorage();
    expect(await screen.findByRole('heading', { level: 1, name: 'Stockage et import' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Stockage et import' })).toHaveAttribute('aria-current', 'page');

    const usage = await screen.findByRole('region', { name: 'Utilisation' });
    expect(within(usage).getByText(/^2,4\sGo$/)).toBeInTheDocument();
    expect(within(usage).getByText(/^384\sGo$/)).toBeInTheDocument();
    expect(within(usage).getByText('128')).toBeInTheDocument();
    const extraction = screen.getByRole('region', { name: 'Extraction du texte' });
    expect(within(extraction).getByText('Actif')).toBeInTheDocument();
    expect(within(extraction).getByText(/2 extractions à la fois\./)).toBeInTheDocument();
    expect(within(extraction).getByText('Après la v0.1')).toBeInTheDocument();
    expect(screen.getByText('/var/lib/lore/files')).toBeInTheDocument();
    expect(screen.getByText(/Plafond fixé par LORE_MAX_UPLOAD_SIZE\s: 1\s024\sMo\./)).toBeInTheDocument();
    expect(screen.getByText('.pdf, .docx, .md, .markdown, .txt')).toBeInTheDocument();
  });

  it('changes the maximum size of a file, within the ceiling of the server', async () => {
    const patches = openStorage();
    const browser = userEvent.setup();
    const size = await screen.findByLabelText('Taille maximale d’un fichier');
    expect(size).toHaveValue(50);

    await browser.clear(size);
    await browser.type(size, '2048');
    await browser.click(screen.getByRole('button', { name: 'Enregistrer' }));
    expect(size).toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByText(/^Choisissez une valeur entre 1 et 1\s024\.$/)).toBeInTheDocument();
    expect(patches).toEqual([]);

    await browser.clear(size);
    await browser.type(size, '100');
    await browser.click(screen.getByRole('button', { name: 'Enregistrer' }));
    expect(await screen.findByText('Modifications enregistrées')).toBeInTheDocument();
    expect(patches).toEqual([{ maxUploadMegabytes: 100 }]);
    expect(size).toHaveValue(100);
  });

  it('saves a size equal to the one in force, so that it stays the choice', async () => {
    const patches = openStorage();
    const browser = userEvent.setup();
    await screen.findByLabelText('Taille maximale d’un fichier');

    await browser.click(screen.getByRole('button', { name: 'Enregistrer' }));

    expect(await screen.findByText('Modifications enregistrées')).toBeInTheDocument();
    expect(patches).toEqual([{ maxUploadMegabytes: 50 }]);
  });

  it('says why the extraction does not work', async () => {
    openStorage({ ...storage, extraction: { active: true, workers: 2, problem: 'Cannot run program "java"' } });
    const extraction = await screen.findByRole('region', { name: 'Extraction du texte' });
    expect(within(extraction).getByText('En panne')).toBeInTheDocument();
    expect(within(extraction).getByText(/n’a pas pu démarrer\s: Cannot run program "java"/)).toBeInTheDocument();
  });
});
