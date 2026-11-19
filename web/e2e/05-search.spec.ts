import { markdown } from './files';
import { expect, keepOnly, PEOPLE, t, test } from './lore';

// Journey 5, searching, in Chromium, Firefox and WebKit. Christ Koffi, reader of the space, goes
// to a document with the palette, pastes an error, narrows the results with the filters and their
// counts, opens a result at its passage, goes back to the results, then searches for nothing.
test('E1 E2 E3 E4 E5: Christ Koffi searches for an error of connection to the database', async ({ as, api, audit, browserName }) => {
  const space = `Recherche ${browserName}`;
  const host = `db-${browserName}.dada.group`;
  const wilfried = await api('wilfried');
  const created = await wilfried.createSpace(space, `recherche-${browserName}`);
  await wilfried.addMember(created.id, await wilfried.userId(PEOPLE.christ.email), 'READER');
  const incident = await wilfried.importFile(created.id, markdown('incident-sauvegarde.md', 'Incident : sauvegarde de la base refusée', [
    ['Constat', `La sauvegarde de 2 h a échoué :\n\n\`\`\`\npg_dump: error: connection to server at "${host}" (10.20.4.12), port 5432 failed: FATAL:  password authentication failed for user "sauvegarde"\n\`\`\``],
    ['Cause', 'Le mot de passe du rôle a changé sans mise à jour du fichier .pgpass.'],
  ]), { category: 'INCIDENT_REPORT', status: 'PUBLISHED' });
  await wilfried.importFile(created.id, markdown('purge-journaux.md', 'Purge des journaux de la base', [
    ['Quand', `Quand ${host} répond password authentication failed après une rotation, purgez d'abord les journaux.`],
    ['Comment', 'Supprimez les fichiers de plus de 30 jours.'],
  ]), { category: 'OPERATING_PROCEDURE', status: 'PUBLISHED' });
  const christ = await as('christ');
  await christ.goto('/');
  await expect(christ.getByRole('heading', { level: 1, name: t('home.hello', { name: 'Christ' }) })).toBeVisible();

  await test.step('E1: the palette leads to the document', async () => {
    await christ.keyboard.press('Control+k');
    const palette = christ.getByRole('dialog', { name: t('palette.label') });
    await palette.getByRole('combobox', { name: t('palette.label') }).fill('purge des journaux');
    const option = palette.getByRole('option', { name: new RegExp(`Purge des journaux de la base.*${space}`) });
    await expect(option).toBeVisible();
    await audit(christ, 'palette');
    await option.click();
    await expect(christ.getByRole('heading', { level: 1, name: 'Purge des journaux de la base' })).toBeVisible();
  });

  const query = `connection to server at "${host}" failed`;
  await test.step('E2: the pasted error finds the incident report', async () => {
    await christ.getByRole('navigation', { name: t('nav.environment') }).getByRole('link', { name: t('nav.search') }).click();
    const field = christ.getByRole('searchbox', { name: t('search.field') });
    await field.fill(query);
    await field.press('Enter');
    const first = christ.getByRole('main').getByRole('article').first();
    await expect(first.getByRole('link', { name: incident.title })).toBeVisible();
    await expect(first.locator('mark').first()).toBeVisible();
  });

  await test.step('E3: the filters and their counts', async () => {
    const field = christ.getByRole('searchbox', { name: t('search.field') });
    await field.fill('password authentication failed');
    await field.press('Enter');
    const filters = christ.getByRole('group', { name: t('search.filters') });
    // Alone in the results, the space of this journey is already the only one kept.
    await keepOnly(christ, filters.getByRole('button', { name: new RegExp(`^${t('search.facet.space')}`) }),
      t('search.facet.space'), new RegExp(`^${space}`));
    const results = christ.getByRole('main').getByRole('article');
    await expect(results).toHaveCount(2);
    for (const result of await results.all()) await expect(result).toContainText(space);
    await filters.getByRole('button', { name: t('search.facet.category'), exact: true }).click();
    const categories = christ.getByRole('menu', { name: t('search.facet.category') });
    // The counts are those of the results without filters: one per journey running.
    await expect(categories.getByRole('menuitemcheckbox', { name: new RegExp(`^${t('category.INCIDENT_REPORT')}\\s*\\d+$`) })).toBeVisible();
    await expect(categories.getByRole('menuitemcheckbox', { name: new RegExp(`^${t('category.OPERATING_PROCEDURE')}\\s*\\d+$`) })).toBeVisible();
    await audit(christ, 'Category filter open');
    if (await categories.isVisible()) await christ.keyboard.press('Escape');
    await keepOnly(christ, filters.getByRole('button', { name: new RegExp(`^${t('search.facet.category')}`) }),
      t('search.facet.category'), new RegExp(`^${t('category.INCIDENT_REPORT')}`));
    await expect(christ).toHaveURL(/category=INCIDENT_REPORT/);
    await expect(results).toHaveCount(1);
    await expect(results.first()).toContainText(incident.title);
    await audit(christ, 'filtered results');
  });

  await test.step('E4: the passage opened, then back to the results', async () => {
    const results = christ.url();
    await christ.getByRole('main').getByRole('article').first().getByRole('link', { name: incident.title }).click();
    const found = christ.getByRole('main').getByRole('status').filter({ hasText: t('search.backToResults') });
    await expect(found).toContainText('Constat');
    await expect(christ.locator('.doc-body mark').first()).toBeInViewport();
    await found.getByRole('button', { name: t('search.backToResults') }).click();
    await expect(christ).toHaveURL(results);
    await expect(christ.getByRole('main').getByRole('article')).toHaveCount(1);
  });

  await test.step('E5: nothing matches', async () => {
    await christ.goto('/search?q=' + encodeURIComponent(`kubernetes-${browserName}-inconnu`));
    await expect(christ.getByRole('heading', { name: t('search.none', { query: `kubernetes-${browserName}-inconnu` }) })).toBeVisible();
    await expect(christ.getByText(t('search.noneHelp'))).toBeVisible();
    await audit(christ, 'search without results');
  });
});
