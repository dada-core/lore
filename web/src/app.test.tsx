import { QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter } from 'react-router';
import { RouterProvider } from 'react-router/dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { setUnauthenticatedHandler } from './api/client';
import { queryClient } from './api/queryClient';
import { routes } from './router';
import { expireSession, forgetSignOutReason } from './session/session';
import { amenan, fakeServer, json, noContent, organization } from './test/server';
import { ToastProvider } from './ui/Toast';

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
  setUnauthenticatedHandler(expireSession);
  window.scrollTo = () => {};
});

afterEach(() => {
  queryClient.clear();
  forgetSignOutReason();
  vi.unstubAllGlobals();
});

describe('access', () => {
  it('leads a fresh installation to the initial setup', async () => {
    fakeServer({
      'GET /api/v1/session': () => json({ setupRequired: true, organization: null, user: null }),
      'GET /api/v1/setup': () => json({ required: true, publicUrl: null, publicUrlFromEnvironment: false }),
    });
    const router = open('/admin/members');
    expect(await screen.findByRole('heading', { level: 1 })).toHaveTextContent(/Set up Lore|Configurer Lore/);
    expect(router.state.location.pathname).toBe('/setup');
  });

  it('asks to sign in, then opens the requested address', async () => {
    let user: typeof amenan | null = null;
    fakeServer({
      'GET /api/v1/session': () => json({ setupRequired: false, organization, user }),
      'POST /api/v1/auth/login': async (request) => {
        const body = (await request.json()) as { email: string; password: string };
        if (body.password !== 'bon-mot-de-passe') {
          return json({ status: 401, code: 'invalid_credentials', remainingAttempts: 2, lockoutMinutes: 15 }, 401);
        }
        user = amenan;
        return json({ setupRequired: false, organization, user });
      },
      'GET /api/v1/account': () => json({ displayName: amenan.displayName, email: amenan.email, locale: null, interfaceLocale: 'fr', role: 'ADMIN', passwordMinLength: 12 }),
      'GET /api/v1/account/sessions': () => json([]),
    });
    const router = open('/account');

    expect(await screen.findByRole('heading', { name: 'Connexion à Lore' })).toBeInTheDocument();
    expect(router.state.location.search).toBe('?next=%2Faccount');
    expect(screen.getByText('DadaGroup')).toBeInTheDocument();

    const browser = userEvent.setup();
    await browser.type(screen.getByLabelText('E-mail'), 'amenan.souaga@dada.group');
    await browser.type(screen.getByLabelText('Mot de passe'), 'mauvais');
    await browser.click(screen.getByRole('button', { name: 'Se connecter' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'E-mail ou mot de passe incorrect. Après 2 nouvelles tentatives, le compte sera bloqué 15 minutes.',
    );

    await browser.type(screen.getByLabelText('Mot de passe'), 'bon-mot-de-passe');
    await browser.click(screen.getByRole('button', { name: 'Se connecter' }));
    expect(await screen.findByRole('heading', { level: 1, name: 'Profil et préférences' })).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/account');
  });

  it('checks the fields before sending anything', async () => {
    const server = fakeServer({
      'GET /api/v1/session': () => json({ setupRequired: false, organization, user: null }),
    });
    open('/login');
    const browser = userEvent.setup();
    await browser.type(await screen.findByLabelText('E-mail'), 'pas-une-adresse');
    await browser.click(screen.getByRole('button', { name: 'Se connecter' }));
    expect(screen.getByLabelText('E-mail')).toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByLabelText('E-mail')).toHaveFocus();
    expect(server.requests.filter((request) => request.method === 'POST')).toHaveLength(0);
  });

  it('says that the account is locked', async () => {
    fakeServer({
      'GET /api/v1/session': () => json({ setupRequired: false, organization: { ...organization, defaultLocale: 'en' }, user: null }),
      'POST /api/v1/auth/login': () => json({ status: 429, code: 'login_locked', retryAfterMinutes: 60 }, 429),
    });
    open('/login');
    const browser = userEvent.setup();
    await browser.type(await screen.findByLabelText('E-mail'), 'amenan.souaga@dada.group');
    await browser.type(screen.getByLabelText('Password'), 'whatever');
    await browser.click(screen.getByRole('button', { name: 'Sign in' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Too many attempts. Try again in 1 hour, or ask an administrator for a reset link.');
  });
});

describe('signed in', () => {
  const session = () => json({ setupRequired: false, organization, user: { ...amenan, role: 'MEMBER' } });

  it('shows the same page for administration as for an address that does not exist', async () => {
    fakeServer({ 'GET /api/v1/session': session });
    open('/admin/members');
    expect(await screen.findByRole('heading', { name: 'Cette page est introuvable' })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Administration' })).not.toBeInTheDocument();
  });

  it('greets the person and signs out', async () => {
    fakeServer({ 'GET /api/v1/session': session, 'POST /api/v1/auth/logout': () => noContent() });
    const router = open('/');
    expect(await screen.findByRole('heading', { name: 'Bonjour, Amenan' })).toBeInTheDocument();
    await userEvent.setup().click(screen.getByRole('button', { name: 'Se déconnecter' }));
    expect(await screen.findByText('Déconnexion effectuée.')).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/login');
    expect(router.state.location.search).toBe('');
  });

  it('goes back to the sign-in page when the session has expired', async () => {
    fakeServer({
      'GET /api/v1/session': session,
      'GET /api/v1/account': () => json({ status: 401, code: 'unauthenticated' }, 401),
      'GET /api/v1/account/sessions': () => json({ status: 401, code: 'unauthenticated' }, 401),
    });
    const router = open('/account');
    expect(await screen.findByText('Votre session a expiré. Reconnectez-vous pour continuer.')).toBeInTheDocument();
    await waitFor(() => expect(router.state.location.pathname).toBe('/login'));
    expect(router.state.location.search).toBe('?next=%2Faccount');
  });
});

describe('links received', () => {
  it('says that a used invitation link is no longer valid', async () => {
    fakeServer({
      'GET /api/v1/session': () => json({ setupRequired: false, organization, user: null }),
      'POST /api/v1/invitations/inspect': () => json({ status: 404, code: 'invalid_link' }, 404),
    });
    open('/join#token-deja-utilise');
    expect(await screen.findByText(/Ce lien d.invitation n.est plus valable/)).toBeInTheDocument();
  });

  it('creates the account from an invitation and opens Lore', async () => {
    let joined = false;
    fakeServer({
      'GET /api/v1/session': () => json({ setupRequired: false, organization, user: joined ? amenan : null }),
      'POST /api/v1/invitations/inspect': () => json({
        organizationName: 'DadaGroup', invitedBy: 'Wilfried Kouakou', role: 'MEMBER', email: null,
        expiresAt: '2026-11-19T10:00:00Z', passwordMinLength: 12,
      }),
      'POST /api/v1/invitations/accept': async (request) => {
        const body = (await request.json()) as { token: string; displayName: string };
        expect(body.token).toBe('jeton');
        joined = true;
        return json({ setupRequired: false, organization, user: { ...amenan, displayName: body.displayName } }, 201);
      },
    });
    const router = open('/join#jeton');
    expect(await screen.findByRole('heading', { name: 'Rejoindre DadaGroup' })).toBeInTheDocument();
    expect(screen.getByText('Wilfried Kouakou vous invite avec le rôle Membre.')).toBeInTheDocument();

    const browser = userEvent.setup();
    await browser.type(screen.getByLabelText('Nom complet'), 'Amenan Souaga');
    await browser.type(screen.getByLabelText('E-mail professionnel'), 'amenan.souaga@dada.group');
    await browser.type(screen.getByLabelText('Mot de passe'), 'un-mot-de-passe-solide');
    await browser.type(screen.getByLabelText('Confirmation'), 'un-mot-de-passe-solide');
    await browser.click(screen.getByRole('button', { name: 'Créer mon compte' }));

    expect(await screen.findByRole('heading', { name: 'Bonjour, Amenan' })).toBeInTheDocument();
    expect(router.state.location.hash).toBe('');
    expect(screen.getByText('Bienvenue dans DadaGroup')).toBeInTheDocument();
  });
});
