import { QueryClientProvider } from '@tanstack/react-query';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter } from 'react-router';
import { RouterProvider } from 'react-router/dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { queryClient } from '../../api/queryClient';
import { routes } from '../../router';
import { amenan, fakeServer, json, organization } from '../../test/server';
import { ToastProvider } from '../../ui/Toast';

const defaults = {
  name: 'DadaGroup', slug: 'dadagroup', defaultLocale: 'fr', timeZone: 'Europe/Paris',
  publicUrl: 'https://lore.example.com', publicUrlFromEnvironment: false,
  passwordMinLength: 12, loginMaxAttempts: 5, loginLockoutMinutes: 15,
  sessionTimeoutMinutes: 480, sessionTimeoutMaxMinutes: 480, invitationValidityHours: 168,
};

/** Administration with a fake server; returns the bodies of the PATCH requests. */
function openAdmin(path: string, settings = defaults) {
  const patches: unknown[] = [];
  let current = { ...settings };
  fakeServer({
    'GET /api/v1/session': () => json({ setupRequired: false, organization, user: amenan }),
    'GET /api/v1/admin/organization': () => json(current),
    'PATCH /api/v1/admin/organization': async (request) => {
      const changes = (await request.json()) as Record<string, unknown>;
      patches.push(changes);
      current = { ...current, ...changes };
      return json(current);
    },
  });
  render(
    <QueryClientProvider client={queryClient}>
      <ToastProvider>
        <RouterProvider router={createMemoryRouter(routes, { initialEntries: [path] })} />
      </ToastProvider>
    </QueryClientProvider>,
  );
  return patches;
}

beforeEach(() => {
  window.scrollTo = () => {};
});

afterEach(() => {
  queryClient.clear();
  vi.unstubAllGlobals();
});

describe('Security and sign-in', () => {
  it('sends every changed setting, and only those', async () => {
    const patches = openAdmin('/admin/security');
    const browser = userEvent.setup();
    const minimum = await screen.findByLabelText('Longueur minimale');

    await browser.clear(minimum);
    await browser.type(minimum, '16');
    await browser.clear(screen.getByLabelText('Tentatives avant blocage'));
    await browser.type(screen.getByLabelText('Tentatives avant blocage'), '3');
    await browser.selectOptions(screen.getByLabelText('Durée du blocage'), '60');
    await browser.selectOptions(screen.getByLabelText('Expiration après inactivité'), '30');
    await browser.selectOptions(screen.getByLabelText('Validité des liens d’invitation'), '48');
    await browser.click(screen.getByRole('button', { name: 'Enregistrer' }));

    expect(await screen.findByText('Modifications enregistrées')).toBeInTheDocument();
    expect(patches).toEqual([{
      passwordMinLength: 16, loginMaxAttempts: 3, loginLockoutMinutes: 60, sessionTimeoutMinutes: 30, invitationValidityHours: 48,
    }]);
    // The form now shows what the server saved.
    expect(screen.getByLabelText('Longueur minimale')).toHaveValue(16);
    expect(screen.getByLabelText('Durée du blocage')).toHaveValue('60');
  });

  it('refuses values outside the limits before sending', async () => {
    const patches = openAdmin('/admin/security');
    const browser = userEvent.setup();
    const minimum = await screen.findByLabelText('Longueur minimale');
    await browser.clear(minimum);
    await browser.type(minimum, '99');
    await browser.click(screen.getByRole('button', { name: 'Enregistrer' }));

    expect(minimum).toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByText('Choisissez une valeur entre 8 et 64.')).toBeInTheDocument();
    expect(patches).toEqual([]);
  });

  it('only offers session lengths under the ceiling of the environment', async () => {
    openAdmin('/admin/security', { ...defaults, sessionTimeoutMinutes: 60, sessionTimeoutMaxMinutes: 60 });
    const select = await screen.findByLabelText('Expiration après inactivité');
    const options = within(select).getAllByRole('option').map((option) => (option as HTMLOptionElement).value);
    expect(options).toEqual(['15', '30', '60']);
    expect(screen.getByText(/plafond fixé par LORE_SESSION_TIMEOUT/)).toBeInTheDocument();
  });
});

describe('General', () => {
  it('keeps the public address read-only when the environment sets it', async () => {
    const patches = openAdmin('/admin/general', { ...defaults, publicUrlFromEnvironment: true });
    const address = await screen.findByLabelText('Adresse publique');
    expect(address).toHaveAttribute('readonly');
    expect(screen.getByText('Définie par LORE_PUBLIC_URL sur le serveur.')).toBeInTheDocument();

    const browser = userEvent.setup();
    await browser.clear(screen.getByLabelText('Nom'));
    await browser.type(screen.getByLabelText('Nom'), 'DadaGroup Réseaux');
    await browser.selectOptions(screen.getByLabelText('Langue par défaut'), 'en');
    await browser.click(screen.getByRole('button', { name: 'Enregistrer' }));

    await screen.findByText(/Changes saved|Modifications enregistrées/);
    expect(patches).toEqual([{ name: 'DadaGroup Réseaux', defaultLocale: 'en' }]);
  });
});
