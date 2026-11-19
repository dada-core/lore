import { writeFileSync } from 'node:fs';
import { pdf, runbook, RUNBOOK_TITLE } from './files';
import { expect, PASSWORD, PEOPLE, t, test } from './lore';

/** The error Wilfried pastes: the search opens the runbook at section 4.2. */
export const PASTED_ERROR = 'pg_restore role "does not exist"';

// Journey 1, the five minutes. On a fresh installation: the initial setup in the browser, the
// Infrastructure space, a runbook and a PDF imported by the import dialog, then the search of a
// pasted error that opens the runbook at its passage. packaging/five-minutes.sh times it, with
// LORE_E2E_AXE=0 and the durations written to LORE_FIVE_MINUTES_REPORT.
test('A1 A2 C1 E2 E4: Wilfried Kouakou sets Lore up and finds the passage of a runbook', async ({ page, audit }) => {
  const code = process.env.LORE_SETUP_CODE;
  if (!code) throw new Error('LORE_SETUP_CODE: the setup code printed in the logs of the fresh installation');
  const durations: { step: string; seconds: number }[] = [];
  const timed = async (step: string, body: () => Promise<void>) => {
    const start = performance.now();
    await test.step(step, body);
    durations.push({ step, seconds: Math.round((performance.now() - start) / 100) / 10 });
  };
  const wilfried = PEOPLE.wilfried;

  await timed('Initial setup', async () => {
    await page.goto('/');
    await expect(page).toHaveURL(/\/setup$/);
    await expect(page.getByRole('heading', { level: 1, name: t('setup.title') })).toBeVisible();
    await audit(page, 'setup, step 1');
    await page.getByLabel(t('setup.code')).fill(code);
    await page.getByLabel(t('setup.orgName')).fill('DadaGroup');
    await page.getByRole('button', { name: t('common.continue') }).click();

    await expect(page.getByRole('heading', { level: 1, name: t('setup.adminTitle') })).toBeVisible();
    await audit(page, 'setup, step 2');
    await page.getByLabel(t('setup.fullName')).fill(wilfried.name);
    await page.getByLabel(t('setup.email')).fill(wilfried.email);
    await page.getByLabel(t('setup.password'), { exact: true }).fill(PASSWORD);
    await page.getByLabel(t('setup.confirm')).fill(PASSWORD);
    await page.getByRole('button', { name: t('setup.create') }).click();

    await expect(page.getByRole('heading', { level: 1, name: t('setup.readyTitle', { name: 'DadaGroup' }) })).toBeVisible();
    await audit(page, 'setup, step 3');
    await page.getByRole('link', { name: t('setup.open') }).click();
    await expect(page.getByRole('heading', { level: 1, name: t('home.hello', { name: 'Wilfried' }) })).toBeVisible();
    await audit(page, 'home, empty organization');
  });

  await timed('The Infrastructure space', async () => {
    await page.getByRole('main').getByRole('button', { name: t('nav.createSpace') }).click();
    const dialog = page.getByRole('dialog', { name: t('createSpace.title') });
    await audit(page, 'Create a space dialog');
    await dialog.getByLabel(t('space.name'), { exact: true }).fill('Infrastructure');
    await dialog.getByRole('button', { name: t('createSpace.submit') }).click();
    await expect(page).toHaveURL(/\/spaces\/infrastructure$/);
    await expect(page.getByRole('heading', { level: 1, name: 'Infrastructure' })).toBeVisible();
    await audit(page, 'empty space');
  });

  await timed('A runbook and a PDF imported', async () => {
    await page.keyboard.press('i');
    const dialog = page.getByRole('dialog', { name: t('import.title') });
    await expect(dialog).toBeVisible();
    const chooser = page.waitForEvent('filechooser');
    await dialog.getByRole('button', { name: t('import.choose') }).click();
    await (await chooser).setFiles([
      runbook(),
      pdf('plan-de-reprise.pdf', 'Plan de reprise d\'activite', [
        ['Plan de reprise d\'activite de DadaGroup', 'Objectif de reprise : 4 heures.'],
        ['Sauvegardes', 'La base PostgreSQL est sauvegardee chaque nuit a 2 h.'],
        ['Contacts', 'Astreinte : Eli Brou, puis Herve Coulibaly.'],
      ]),
    ]);
    const files = dialog.getByRole('listitem');
    await expect(files).toHaveCount(2);
    for (const file of await files.all()) await expect(file).toContainText(t('import.indexed'), { timeout: 120_000 });
    await audit(page, 'import dialog, files indexed');
    await dialog.getByRole('button', { name: t('import.done') }).click();
    await expect(dialog).toBeHidden();
    await expect(page.getByRole('main').getByRole('link', { name: RUNBOOK_TITLE })).toBeVisible();
  });

  await timed('The pasted error searched', async () => {
    await page.getByRole('navigation', { name: t('nav.environment') }).getByRole('link', { name: t('nav.search') }).click();
    const field = page.getByRole('searchbox', { name: t('search.field') });
    await field.fill(PASTED_ERROR);
    await field.press('Enter');
    const first = page.getByRole('main').getByRole('article').first();
    await expect(first.getByRole('link', { name: RUNBOOK_TITLE })).toBeVisible();
    await expect(first).toContainText('4.2 Erreurs fréquentes');
    await audit(page, 'search results');
  });

  await timed('Opened at the passage', async () => {
    await page.getByRole('main').getByRole('article').first().getByRole('link', { name: RUNBOOK_TITLE }).click();
    await expect(page.getByRole('main').getByRole('status').filter({ hasText: t('search.backToResults') }))
      .toContainText('4.2 Erreurs fréquentes');
    await expect(page.getByRole('heading', { name: '4.2 Erreurs fréquentes' })).toBeInViewport();
    await audit(page, 'document opened at the passage');
  });

  const total = Math.round(durations.reduce((sum, { seconds }) => sum + seconds, 0) * 10) / 10;
  await test.info().attach('durations', { body: JSON.stringify({ durations, total }, null, 2), contentType: 'application/json' });
  if (process.env.LORE_FIVE_MINUTES_REPORT) writeFileSync(process.env.LORE_FIVE_MINUTES_REPORT, JSON.stringify({ durations, total }));
});
