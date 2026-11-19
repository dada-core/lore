import { markdown } from './files';
import { expect, PEOPLE, t, test } from './lore';

const TLS = 'Rotation des certificats TLS internes';

// Journey 6, seeing nothing. Hervé Coulibaly restricts the TLS runbook to Christ Koffi and Elfried
// Yaba. Eli Brou, reader of the same space, finds it nowhere: not in the library, not in the search
// nor its counts, not in the palette; its address answers "Cette page est introuvable". Christ
// still finds it.
test('F2 E2 5.1: a restricted document appears nowhere for Eli Brou', async ({ as, api, audit }) => {
  const wilfried = await api('wilfried');
  const space = await wilfried.createSpace('Sécurité', 'securite');
  await wilfried.addMember(space.id, await wilfried.userId(PEOPLE.herve.email), 'MAINTAINER');
  for (const person of [PEOPLE.eli, PEOPLE.christ, PEOPLE.elfried]) {
    await wilfried.addMember(space.id, await wilfried.userId(person.email), 'READER');
  }
  const herveApi = await api('herve');
  const tls = await herveApi.importFile(space.id, markdown('rotation-tls.md', TLS, [
    ['Calendrier', 'Les certificats de l’autorité interne tournent tous les 90 jours.'],
    ['Procédure', 'Générez la clé sur le bastion, puis déployez le certificat sur tls-interne.dada.group.'],
  ]));
  await herveApi.importFile(space.id, markdown('mots-de-passe.md', 'Politique des mots de passe', [
    ['Longueur', 'Douze caractères au minimum.'],
  ]));

  await test.step('F2: Hervé restricts the document to Christ and Elfried', async () => {
    const herve = await as('herve');
    await herve.goto(`/documents/${tls.id}`);
    await herve.getByRole('button', { name: t('document.restrictAccess') }).click();
    const dialog = herve.getByRole('dialog', { name: t('access.title') });
    await dialog.getByRole('radio', { name: new RegExp(t('access.some')) }).check();
    const people = dialog.getByRole('group', { name: t('access.people') });
    await people.getByRole('checkbox', { name: PEOPLE.christ.email }).check();
    await people.getByRole('checkbox', { name: PEOPLE.elfried.email }).check();
    await audit(herve, 'Access to the document dialog');
    await dialog.getByRole('button', { name: t('common.save') }).click();
    await expect(herve.getByText(t('access.saved'))).toBeVisible();
  });

  await test.step('5.1: Eli sees it neither in the library, nor in the search, nor in the palette', async () => {
    const eli = await as('eli');
    await eli.goto('/spaces/securite');
    const library = eli.getByRole('main');
    await expect(library.getByRole('link', { name: 'Politique des mots de passe' })).toBeVisible();
    await expect(library.getByRole('link', { name: TLS })).toHaveCount(0);
    await expect(library).toContainText(t('library.count.one', { count: 1 }));
    await expect(library).not.toContainText('Rotation');

    await eli.goto('/search?q=' + encodeURIComponent('certificats TLS internes'));
    await expect(eli.getByRole('heading', { name: t('search.none', { query: 'certificats TLS internes' }) })).toBeVisible();
    await eli.goto('/search?q=' + encodeURIComponent('tls-interne.dada.group'));
    await expect(eli.getByRole('heading', { name: t('search.none', { query: 'tls-interne.dada.group' }) })).toBeVisible();
    await eli.goto('/search?q=' + encodeURIComponent('caractères'));
    await expect(eli.getByRole('main').getByRole('article').filter({ hasText: 'Sécurité' })).toHaveCount(1);

    await eli.keyboard.press('Control+k');
    const palette = eli.getByRole('dialog', { name: t('palette.label') });
    await palette.getByRole('combobox', { name: t('palette.label') }).fill('Rotation des certificats');
    await expect(palette.getByRole('option', { name: t('palette.searchFor', { query: 'Rotation des certificats' }) })).toBeVisible();
    await expect(palette.getByRole('option', { name: new RegExp(TLS) })).toHaveCount(0);
    await eli.keyboard.press('Escape');

    await eli.goto(`/documents/${tls.id}`);
    await expect(eli.getByRole('heading', { name: t('notFound.title') })).toBeVisible();
    await audit(eli, 'page not found');
  });

  await test.step('Christ finds it', async () => {
    const christ = await as('christ');
    await christ.goto('/search?q=' + encodeURIComponent('certificats TLS internes'));
    await expect(christ.getByRole('main').getByRole('article').getByRole('link', { name: TLS })).toBeVisible();
  });
});
