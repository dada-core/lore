import { markdown } from './files';
import { expect, PEOPLE, t, test } from './lore';

const DNS = 'Bascule DNS';
const OLD = 'Bascule DNS 2024';

function procedure(withRollback: boolean) {
  const sections: [string, string][] = [
    ['Préparer', 'Baissez la durée de vie des enregistrements à 300 secondes la veille.'],
    ['Basculer', 'Changez l’enregistrement A de portail.dada.group vers le nouveau répartiteur.'],
  ];
  if (withRollback) sections.push(['Retour arrière', 'Remettez l’ancienne adresse si les contrôles échouent.']);
  return markdown('bascule-dns.md', DNS, sections);
}

// Journey 7, the life of a document. Hervé Coulibaly imports a version 2 with its note, which
// becomes current once extracted; reads version 1 from the strata; marks the 2024 procedure as
// outdated, replaced by the new one, then archives it; imports a damaged file, which fails; the
// home page lists it under "À traiter", and the new version under the recent activity.
test('C2 C3 D3 F1 F3 B1: Hervé Coulibaly keeps the DNS switch procedure alive', async ({ as, api, audit }) => {
  const wilfried = await api('wilfried');
  const space = await wilfried.createSpace('Exploitation', 'exploitation');
  await wilfried.addMember(space.id, await wilfried.userId(PEOPLE.herve.email), 'MAINTAINER');
  const herveApi = await api('herve');
  const current = await herveApi.importFile(space.id, procedure(false));
  const old = await herveApi.importFile(space.id, markdown('bascule-dns-2024.md', OLD, [
    ['Basculer', 'Appelez l’hébergeur pour changer l’enregistrement de portail.dada.group.'],
  ]), { status: 'PUBLISHED' });
  const herve = await as('herve');

  await test.step('C3: a version 2 with its note, current once extracted', async () => {
    await herve.goto(`/documents/${current.id}`);
    await herve.getByRole('button', { name: t('version.new') }).click();
    const dialog = herve.getByRole('dialog', { name: t('version.newTitle', { title: DNS }) });
    const chooser = herve.waitForEvent('filechooser');
    await dialog.getByRole('button', { name: t('version.choose') }).click();
    await (await chooser).setFiles(procedure(true));
    await dialog.getByRole('textbox', { name: t('version.note') }).fill('Ajout du retour arrière');
    await audit(herve, 'New version dialog');
    await dialog.getByRole('button', { name: t('version.import', { number: 2 }) }).click();
    await expect(herve.getByText(t('version.imported'))).toBeVisible();
    await expect(herve.getByRole('heading', { name: 'Retour arrière' })).toBeVisible({ timeout: 60_000 });
    const history = herve.getByRole('group', { name: t('versions.label') });
    await expect(history.getByRole('button')).toHaveCount(2);
    await expect(history.getByRole('button').first()).toHaveAccessibleName(new RegExp(`^${t('versions.other', { number: 2 })}\\b`));
    await expect(herve.getByText(t('versions.current', { number: 2 }))).toBeVisible();
    await audit(herve, 'document in version 2');
  });

  await test.step('D3: version 1 read from the strata', async () => {
    const history = herve.getByRole('group', { name: t('versions.label') });
    await history.getByRole('button', { name: new RegExp(`^${t('versions.other', { number: 1 })}\\b`) }).click();
    await herve.getByRole('region', { name: t('document.versions') }).getByRole('link', { name: t('versions.show'), exact: true }).click();
    await expect(herve).toHaveURL(new RegExp(`/documents/${current.id}/versions/1$`));
    await expect(herve.getByRole('main')).toContainText(t('version.viewing', { number: 1, date: '' }).split(' du ')[0]);
    await expect(herve.getByRole('heading', { name: 'Basculer' })).toBeVisible();
    await expect(herve.getByRole('heading', { name: 'Retour arrière' })).toHaveCount(0);
    await audit(herve, 'older version');
    await herve.getByRole('link', { name: t('version.backToCurrent') }).click();
    await expect(herve).toHaveURL(new RegExp(`/documents/${current.id}$`));
  });

  await test.step('F1: the 2024 procedure outdated, replaced by the new one', async () => {
    await herve.goto(`/documents/${old.id}`);
    await herve.getByRole('button', { name: t('status.changeOf', { status: t('docStatus.PUBLISHED') }) }).click();
    const dialog = herve.getByRole('dialog', { name: t('status.change') });
    await dialog.getByRole('radio', { name: new RegExp(`^${t('docStatus.OBSOLETE')}`) }).check();
    await dialog.getByRole('textbox', { name: t('status.replacedBy') }).fill(DNS);
    await dialog.getByRole('list', { name: t('status.replacedBy') }).getByRole('button', { name: new RegExp(`^${DNS}\\s+Exploitation$`) }).click();
    await audit(herve, 'Change the status dialog, replacement chosen');
    await dialog.getByRole('button', { name: t('common.save') }).click();
    await expect(herve.getByText(t('status.saved'))).toBeVisible();
    await expect(herve.getByRole('main')).toContainText(t('status.useInstead'));
    await expect(herve.getByRole('main').getByRole('link', { name: DNS, exact: true }).first()).toBeVisible();
    await audit(herve, 'outdated document');

    await herve.goto('/search?q=' + encodeURIComponent('portail.dada.group'));
    const results = herve.getByRole('main').getByRole('article');
    await expect(results).toHaveCount(2);
    await expect(results.last()).toContainText(OLD);
    await expect(results.last()).toContainText(t('docStatus.OBSOLETE'));
  });

  await test.step('F3: the 2024 procedure archived', async () => {
    await herve.goto(`/documents/${old.id}`);
    await herve.getByRole('button', { name: t('status.changeOf', { status: t('docStatus.OBSOLETE') }) }).click();
    const dialog = herve.getByRole('dialog', { name: t('status.change') });
    await dialog.getByRole('radio', { name: new RegExp(`^${t('docStatus.ARCHIVED')}`) }).check();
    await dialog.getByRole('button', { name: t('common.save') }).click();
    await expect(herve.getByText(t('status.saved'))).toBeVisible();
    await expect(herve.getByRole('main')).toContainText(t('status.archivedSince', { date: '' }).split(' depuis ')[0]);
    await audit(herve, 'archived document');
    await herve.goto('/search?q=' + encodeURIComponent('portail.dada.group'));
    await expect(herve.getByRole('main').getByRole('article')).toHaveCount(1);
  });

  await test.step('C2: a damaged file fails', async () => {
    await herve.goto('/spaces/exploitation');
    await expect(herve.getByRole('heading', { level: 1, name: 'Exploitation' })).toBeVisible();
    await herve.keyboard.press('i');
    const dialog = herve.getByRole('dialog', { name: t('import.title') });
    const chooser = herve.waitForEvent('filechooser');
    await dialog.getByRole('button', { name: t('import.choose') }).click();
    await (await chooser).setFiles({ name: 'rapport-endommage.pdf', mimeType: 'application/pdf', buffer: Buffer.from('%PDF-1.7\n1 0 obj\n<< /Type /Catalog') });
    const file = dialog.getByRole('listitem').filter({ hasText: 'rapport-endommage.pdf' });
    await expect(file).toContainText(t('extraction.FAILED'), { timeout: 120_000 });
    await audit(herve, 'import dialog, failure');
    await dialog.getByRole('button', { name: t('import.done') }).click();
  });

  await test.step('B1: the home page lists the failure and the new version', async () => {
    await herve.goto('/');
    const attention = herve.getByRole('region', { name: t('home.attention') });
    // Named after its file until its text is read.
    await expect(attention).toContainText('Rapport endommage');
    await expect(attention).toContainText(t('home.attention.failed'));
    const activity = herve.getByRole('region', { name: t('home.activity') });
    await expect(activity).toContainText(t('home.activity.document.version_imported', { name: PEOPLE.herve.name, version: 2, title: DNS }));
    await audit(herve, 'home page with items to handle');
  });
});
