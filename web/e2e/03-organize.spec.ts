import { markdown, plainText } from './files';
import { expect, PEOPLE, t, test } from './lore';

// Journey 3, organizing. Hervé Coulibaly creates the Réseau space, adds Christ Koffi as a reader,
// makes a folder and files a document in it, sorts the grid, opens the kanban, then archives the
// space, which Christ no longer sees.
test('B2 B4 B5 B6 D1: Hervé Coulibaly organizes the Réseau space, then archives it', async ({ as, api, audit }) => {
  const herve = await as('herve');

  await test.step('B2: Hervé creates the Réseau space', async () => {
    await herve.goto('/');
    await herve.getByRole('main').getByRole('button', { name: t('nav.createSpace') }).click();
    const dialog = herve.getByRole('dialog', { name: t('createSpace.title') });
    await dialog.getByLabel(t('space.name'), { exact: true }).fill('Réseau');
    await dialog.getByRole('button', { name: t('createSpace.submit') }).click();
    await expect(herve).toHaveURL(/\/spaces\/reseau$/);
    await expect(herve.getByText(t('createSpace.created'))).toBeVisible();
  });

  await test.step('B4: Hervé adds Christ Koffi as a reader', async () => {
    await herve.goto('/spaces/reseau/members');
    await herve.getByRole('button', { name: t('spaceMembers.add') }).click();
    const dialog = herve.getByRole('dialog', { name: t('spaceMembers.add') });
    // By the address: another person of the journeys may bear the same name.
    await dialog.getByRole('checkbox', { name: PEOPLE.christ.email }).check();
    await dialog.getByLabel(t('addMembers.role')).selectOption({ label: t('spaceRole.READER') });
    await audit(herve, 'Add members dialog');
    await dialog.getByRole('button', { name: t('addMembers.submit.one', { count: 1 }) }).click();
    await expect(dialog).toBeHidden();
    await expect(herve.getByRole('row', { name: PEOPLE.christ.email })).toContainText(t('spaceRole.READER'));
    await audit(herve, 'members of the space');
  });

  const { id: spaceId } = await (await api('herve')).get<{ id: string }>('/api/v1/spaces/by-slug/reseau');
  const herveApi = await api('herve');
  const rules = await herveApi.importFile(spaceId, markdown('regles-pare-feu.md', 'Règles du pare-feu', [
    ['Entrées', 'Seuls les ports 22, 80 et 443 sont ouverts depuis l\'extérieur.'],
    ['Sorties', 'Tout est permis vers le réseau de DadaGroup.'],
  ]));
  await herveApi.importFile(spaceId, plainText('plan-adressage.txt', 'Plan d’adressage IP\n\n10.20.0.0/16 : serveurs de production.\n'));

  await test.step('B5: Hervé makes the Pare-feu folder and files the rules in it', async () => {
    await herve.goto('/spaces/reseau');
    await herve.getByRole('button', { name: t('library.newFolder') }).click();
    const folder = herve.getByRole('dialog', { name: t('library.newFolder') });
    await folder.getByLabel(t('folder.name')).fill('Pare-feu');
    await audit(herve, 'New folder dialog');
    await folder.getByRole('button', { name: t('folder.create') }).click();
    await expect(folder).toBeHidden();

    await herve.getByRole('link', { name: 'Règles du pare-feu' }).click();
    await expect(herve).toHaveURL(new RegExp(`/documents/${rules.id}`));
    await herve.getByRole('button', { name: t('document.folderOf', { name: t('library.noFolder') }) }).click();
    const move = herve.getByRole('dialog', { name: t('document.moveTitle') });
    await move.getByLabel(t('document.moveTo')).selectOption({ label: 'Pare-feu' });
    await audit(herve, 'Move the document dialog');
    await move.getByRole('button', { name: t('document.moveSubmit') }).click();
    await expect(herve.getByRole('button', { name: t('document.folderOf', { name: 'Pare-feu' }) })).toBeVisible();
  });

  await test.step('D1: the grid sorted by title, then the kanban', async () => {
    await herve.goto('/spaces/reseau');
    const grid = herve.getByRole('main').getByRole('table');
    await expect(grid.getByRole('button', { name: /^Dossier Pare-feu/ })).toBeVisible();
    await herve.getByRole('button', { name: t('library.sortBy', { field: t('library.sort.updated') }) }).click();
    await herve.getByRole('menuitemradio', { name: t('library.sort.title') }).click();
    const sort = herve.getByRole('button', { name: t('library.sortBy', { field: t('library.sort.title') }) });
    await sort.click();
    await herve.getByRole('menuitemradio', { name: t('library.sort.asc') }).click();
    await sort.click();
    await expect(herve.getByRole('menuitemradio', { name: t('library.sort.title') })).toBeChecked();
    await expect(herve.getByRole('menuitemradio', { name: t('library.sort.asc') })).toBeChecked();
    await herve.keyboard.press('Escape');
    await audit(herve, 'grid sorted by title');
    await herve.getByRole('link', { name: t('library.view.kanban') }).click();
    await expect(herve.getByRole('region', { name: /Brouillon/ }).getByRole('link')).toHaveCount(2);
    await audit(herve, 'kanban');
  });

  await test.step('B6: Hervé archives the space, which Christ no longer sees', async () => {
    const christ = await as('christ');
    await christ.goto('/spaces');
    await expect(christ.getByRole('main').getByRole('link', { name: /Réseau/ })).toBeVisible();
    await audit(christ, 'list of the spaces');

    await herve.goto('/spaces/reseau/settings');
    await audit(herve, 'settings of the space');
    await herve.getByRole('button', { name: t('spaceSettings.archiveButton') }).click();
    await expect(herve.getByText(t('spaceSettings.archived'))).toBeVisible();
    await expect(herve.getByRole('button', { name: t('spaceSettings.unarchiveButton') })).toBeVisible();

    await christ.reload();
    await expect(christ.getByRole('heading', { level: 1 })).toBeVisible();
    await expect(christ.getByRole('main').getByRole('link', { name: /Réseau/ })).toHaveCount(0);
    await christ.goto('/spaces/reseau');
    await expect(christ.getByRole('heading', { name: t('notFound.title') })).toBeVisible();
  });
});
