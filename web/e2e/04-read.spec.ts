import { pdf, runbook, RUNBOOK_TITLE, scannedPdf } from './files';
import { copied, expect, PEOPLE, t, test } from './lore';

// Journey 4, reading, in Chromium, Firefox and WebKit: pdf.js, selection and keys differ between
// engines. Elfried Yaba opens a runbook at a section by its link, edits its properties and
// downloads the original; opens a PDF of several pages at the page found; describes a scanned PDF,
// then finds it by its description.
test('D2 D4 D5 C4: Elfried Yaba reads a runbook, a PDF and a scanned PDF', async ({ as, api, audit, browserName }) => {
  const space = `Lecture ${browserName}`;
  const wilfried = await api('wilfried');
  const created = await wilfried.createSpace(space, `lecture-${browserName}`);
  await wilfried.addMember(created.id, await wilfried.userId(PEOPLE.elfried.email), 'CONTRIBUTOR');
  const elfriedApi = await api('elfried');
  const file = runbook();
  const doc = await elfriedApi.importFile(created.id, file);
  const guide = await elfriedApi.importFile(created.id, pdf('guide-vpn.pdf', 'Guide VPN', [
    ['Guide du VPN de DadaGroup', 'Pour les personnes en astreinte.'],
    ['Installation', 'Installez le client fourni par le service informatique.'],
    ['Configuration', 'Le tunnel WireGuard passe par la passerelle vpn.dada.group.'],
    ['Depannage', 'Redemarrez le client, puis verifiez la connexion.'],
  ]));
  const scan = await elfriedApi.importFile(created.id, scannedPdf('schema-baie.pdf', 'Schema de baie', 2));
  expect(scan.currentVersion.extractionStatus).toBe('SCANNED');
  const elfried = await as('elfried');

  await test.step('D2: the runbook opened at a section by its link', async () => {
    await elfried.goto(`/documents/${doc.id}`);
    await expect(elfried.getByRole('heading', { level: 1, name: RUNBOOK_TITLE })).toBeVisible();
    const contents = elfried.getByRole('navigation', { name: t('reader.onThisPage') });
    await contents.getByRole('link', { name: '4.2 Erreurs fréquentes' }).click();
    const section = elfried.getByRole('heading', { name: '4.2 Erreurs fréquentes' });
    await expect(section).toBeInViewport();
    await audit(elfried, 'Markdown document');

    // Copied, or shown to copy by hand when the browser refuses the clipboard.
    await elfried.getByRole('button', { name: t('reader.copySectionLink', { title: '4.2 Erreurs fréquentes' }) }).click();
    await expect(elfried.getByText(t('reader.sectionLinkCopied'))
      .or(elfried.getByRole('dialog', { name: t('reader.sectionLinkTitle') }))).toBeVisible();
    const link = await copied(elfried);
    expect(link).toMatch(new RegExp(`/documents/${doc.id}\\?v=1#.+`));
    await elfried.keyboard.press('Escape');
    const reader = await as('elfried');
    await reader.goto(link);
    await expect(reader.getByRole('heading', { name: '4.2 Erreurs fréquentes' })).toBeInViewport();
    await reader.close();
  });

  await test.step('D4: title, category and language', async () => {
    await elfried.getByRole('button', { name: t('document.editTitle') }).click();
    const title = elfried.getByRole('textbox', { name: t('document.titleLabel') });
    await title.fill('Restaurer PostgreSQL depuis la sauvegarde de la nuit');
    await title.press('Enter');
    await expect(elfried.getByText(t('document.titleSaved'))).toBeVisible();
    await expect(elfried.getByRole('heading', { level: 1, name: 'Restaurer PostgreSQL depuis la sauvegarde de la nuit' })).toBeVisible();

    await elfried.getByRole('button', { name: t('document.categoryOf', { name: t('category.none') }) }).click();
    await elfried.getByRole('menuitemradio', { name: t('category.RUNBOOK') }).click();
    await expect(elfried.getByRole('button', { name: t('document.categoryOf', { name: t('category.RUNBOOK') }) })).toBeVisible();

    await elfried.getByRole('button', { name: t('language.menu', { name: t('language.fr') }) }).click();
    await elfried.getByRole('menuitemradio', { name: t('language.en') }).click();
    const confirm = elfried.getByRole('dialog', { name: t('language.confirmTitle') });
    await audit(elfried, 'Change the language dialog');
    await confirm.getByRole('button', { name: t('language.confirm') }).click();
    await expect(elfried.getByText(t('language.changed'))).toBeVisible();
    await expect(elfried.getByRole('button', { name: t('language.menu', { name: t('language.en') }) })).toBeVisible();
    await audit(elfried, 'document edited');
  });

  await test.step('D5: the original downloaded as it was', async () => {
    const download = elfried.waitForEvent('download');
    await elfried.getByRole('link', { name: t('reader.download') }).click();
    const saved = await (await download).createReadStream();
    const chunks: Buffer[] = [];
    for await (const chunk of saved) chunks.push(chunk as Buffer);
    expect(Buffer.concat(chunks).equals(file.buffer)).toBe(true);
  });

  await test.step('D2: the PDF opened at the page found, words highlighted', async () => {
    await elfried.goto('/search?q=wireguard');
    const result = elfried.getByRole('main').getByRole('article').filter({ hasText: space });
    await expect(result).toContainText(t('search.place.page', { page: 3 }));
    await result.getByRole('link', { name: guide.title }).click();
    await expect(elfried).toHaveURL(new RegExp(`/documents/${guide.id}`));
    await expect(elfried.getByRole('main').getByRole('status').filter({ hasText: t('search.backToResults') }))
      .toContainText(t('reader.atPage', { page: 3 }));
    const pages = elfried.getByRole('region', { name: t('reader.pdfRegion', { title: guide.title }) });
    const third = pages.getByRole('region').nth(2);
    await expect(third.locator('mark').first()).toBeVisible();
    await expect(third).toBeInViewport({ ratio: 0.3 });
    await audit(elfried, 'PDF document opened at the page');
  });

  await test.step('C4: the scanned PDF described, then found by its description', async () => {
    await elfried.goto(`/documents/${scan.id}`);
    await expect(elfried.getByRole('complementary', { name: t('document.properties') })).toContainText(t('extraction.SCANNED'));
    await audit(elfried, 'scanned document');
    // Offered twice for a scanned document: under its title, and in the notice that explains it.
    await elfried.getByRole('button', { name: t('document.addDescription') }).first().click();
    const dialog = elfried.getByRole('dialog', { name: t('document.addDescription') });
    await dialog.getByRole('textbox', { name: t('document.description') }).fill('Schéma de la baie B12, salle serveurs du deuxième étage.');
    await audit(elfried, 'Add a description dialog');
    await dialog.getByRole('button', { name: t('common.save') }).click();
    await expect(elfried.getByText(t('document.descriptionSaved'))).toBeVisible();

    await elfried.goto('/search?q=B12');
    await expect(elfried.getByRole('main').getByRole('article').filter({ hasText: space })
      .getByRole('link', { name: scan.title })).toBeVisible();
  });
});
