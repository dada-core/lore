import { devices, type Locator, type Page } from '@playwright/test';
import { markdown } from './files';
import { expect, expectVisibleFocus, PEOPLE, t, test } from './lore';

/** Moves the focus with Tab until it reaches the target, as a person on the keyboard does. */
async function tabTo(page: Page, target: Locator, browserName: string) {
  // WebKit, like Safari, only tabs to the fields of a form unless Alt is held.
  const key = browserName === 'webkit' ? 'Alt+Tab' : 'Tab';
  for (let presses = 0; presses < 40; presses++) {
    if (await target.evaluate((element) => element === document.activeElement)) return;
    await page.keyboard.press(key);
  }
  throw new Error('Tab never reached the element');
}

// Journey 9, with the keyboard and on a phone, in Chromium, Firefox and WebKit. Eli Brou goes to a
// document with the palette, imports a file and changes a status with the keyboard alone, moves
// through the strata with the arrows and folds the panels with [ and ]; then, on a phone, opens
// the sidebar as a drawer and a dialog in full screen.
test('5.5 5.6: Eli Brou with the keyboard alone, then on a phone', async ({ as, api, audit, browserName }) => {
  const space = `Clavier ${browserName}`;
  const slug = `clavier-${browserName}`;
  const title = `Inventaire des serveurs (${browserName})`;
  const wilfried = await api('wilfried');
  const created = await wilfried.createSpace(space, slug);
  await wilfried.addMember(created.id, await wilfried.userId(PEOPLE.eli.email), 'CONTRIBUTOR');
  const eliApi = await api('eli');
  const inventory = markdown('inventaire.md', title, [['Production', 'Trois serveurs PostgreSQL, deux répartiteurs.']]);
  const document = await eliApi.importFile(created.id, inventory);
  await eliApi.newVersion(document.id, markdown('inventaire.md', title, [
    ['Production', 'Trois serveurs PostgreSQL, deux répartiteurs.'],
    ['Préproduction', 'Un serveur PostgreSQL.'],
  ]), 'Ajout de la préproduction');
  const eli = await as('eli');

  await test.step('The palette with the keyboard', async () => {
    await eli.goto('/');
    const opener = eli.getByRole('complementary', { name: t('nav.main') }).getByRole('button', { name: new RegExp(t('search.openPalette')) });
    await opener.focus();
    await eli.keyboard.press('Enter');
    const palette = eli.getByRole('dialog', { name: t('palette.label') });
    const field = palette.getByRole('combobox', { name: t('palette.label') });
    await expect(field).toBeFocused();
    await eli.keyboard.press('Escape');
    await expect(palette).toBeHidden();
    await expect(opener).toBeFocused();

    await eli.keyboard.press('Control+k');
    await expect(field).toBeFocused();
    await eli.keyboard.type('inventaire des serveurs');
    // The name of the option holds the title among other words: a text is looked for, not a pattern.
    const option = palette.getByRole('option', { name: title });
    await expect(option).toBeVisible();
    for (let presses = 0; presses < 10 && (await option.getAttribute('aria-selected')) !== 'true'; presses++) {
      await eli.keyboard.press('ArrowDown');
    }
    await expect(option).toHaveAttribute('aria-selected', 'true');
    await eli.keyboard.press('Enter');
    await expect(eli.getByRole('heading', { level: 1, name: title })).toBeVisible();
  });

  await test.step('The shortcuts keep quiet while typing', async () => {
    await eli.goto('/search');
    const field = eli.getByRole('searchbox', { name: t('search.field') });
    await field.focus();
    await eli.keyboard.type('i[');
    await expect(field).toHaveValue('i[');
    await expect(eli.getByRole('dialog')).toHaveCount(0);
    await expect(eli.getByRole('complementary', { name: t('nav.main') })).toBeVisible();
  });

  await test.step('The import with the keyboard alone', async () => {
    await eli.goto(`/spaces/${slug}`);
    await eli.getByRole('heading', { level: 1, name: space }).waitFor();
    await eli.keyboard.press('i');
    const dialog = eli.getByRole('dialog', { name: t('import.title') });
    await expect(dialog).toBeVisible();
    const choose = dialog.getByRole('button', { name: t('import.choose') });
    await tabTo(eli, choose, browserName);
    await expectVisibleFocus(eli);
    const chooser = eli.waitForEvent('filechooser');
    await eli.keyboard.press('Enter');
    await (await chooser).setFiles(markdown('astreinte.md', `Tableau d’astreinte (${browserName})`, [['Semaine 47', 'Eli Brou']]));
    await expect(dialog.getByRole('listitem')).toContainText(t('import.indexed'), { timeout: 120_000 });
    const done = dialog.getByRole('button', { name: t('import.done') });
    await tabTo(eli, done, browserName);
    await expectVisibleFocus(eli);
    await eli.keyboard.press('Enter');
    await expect(dialog).toBeHidden();
  });

  await test.step('The status with the keyboard, the focus back on the button', async () => {
    await eli.goto(`/documents/${document.id}`);
    const status = eli.getByRole('button', { name: t('status.changeOf', { status: t('docStatus.DRAFT') }) });
    await status.focus();
    await eli.keyboard.press('Enter');
    const dialog = eli.getByRole('dialog', { name: t('status.change') });
    await expect(dialog).toBeVisible();
    await tabTo(eli, dialog.getByRole('radio', { name: new RegExp(`^${t('docStatus.DRAFT')}`) }), browserName);
    await eli.keyboard.press('ArrowDown');
    await expect(dialog.getByRole('radio', { name: new RegExp(`^${t('docStatus.PUBLISHED')}`) })).toBeChecked();
    await expectVisibleFocus(eli);
    await tabTo(eli, dialog.getByRole('button', { name: t('common.save') }), browserName);
    await eli.keyboard.press('Enter');
    await expect(dialog).toBeHidden();
    // Said in a live region, which a screen reader reads out.
    await expect(eli.getByRole('status').filter({ hasText: t('status.saved') })).toBeAttached();
    await expect(eli.getByRole('button', { name: t('status.changeOf', { status: t('docStatus.PUBLISHED') }) })).toBeFocused();
  });

  await test.step('The strata with the arrows', async () => {
    const history = eli.getByRole('group', { name: t('versions.label') });
    const strata = history.getByRole('button');
    await strata.first().focus();
    await eli.keyboard.press('ArrowDown');
    await expect(strata.nth(1)).toBeFocused();
    await expectVisibleFocus(eli);
    await eli.keyboard.press('ArrowUp');
    await expect(strata.first()).toBeFocused();
  });

  await test.step('[ and ] fold the panels', async () => {
    await eli.getByRole('heading', { level: 1, name: title }).focus();
    const sidebar = eli.getByRole('complementary', { name: t('nav.main') });
    const properties = eli.getByRole('complementary', { name: t('document.properties') });
    await eli.keyboard.press('[');
    await expect(sidebar).toBeHidden();
    await eli.keyboard.press('[');
    await expect(sidebar).toBeVisible();
    await eli.keyboard.press(']');
    await expect(properties).toBeHidden();
    await eli.keyboard.press(']');
    await expect(properties).toBeVisible();
  });

  await test.step('On a phone: the sidebar as a drawer, the dialogs in full screen', async () => {
    // WebKit as an iPhone; Firefox has no mobile mode, Chromium is given one, both at 375 px.
    const iphone = devices['iPhone 13'];
    const mobile = await as('eli', browserName === 'webkit'
      ? { viewport: iphone.viewport, userAgent: iphone.userAgent, deviceScaleFactor: iphone.deviceScaleFactor, isMobile: true, hasTouch: true }
      : { viewport: { width: 375, height: 812 }, isMobile: browserName === 'chromium', hasTouch: browserName === 'chromium' });
    await mobile.goto(`/documents/${document.id}`);
    await expect(mobile.getByRole('heading', { level: 1, name: title })).toBeVisible();
    const sidebar = mobile.getByRole('complementary', { name: t('nav.main') });
    await expect(sidebar).toBeHidden();
    const menu = mobile.getByRole('button', { name: t('nav.showSidebar') });
    await menu.click();
    await expect(menu).toHaveAttribute('aria-expanded', 'true');
    await expect(sidebar).toBeVisible();
    await expect(sidebar.locator(':focus')).toHaveCount(1);
    await mobile.keyboard.press('Escape');
    await expect(sidebar).toBeHidden();
    await expect(menu).toBeFocused();

    await mobile.getByRole('button', { name: t('version.new') }).click();
    const dialog = mobile.getByRole('dialog', { name: t('version.newTitle', { title }) });
    await expect(dialog).toBeVisible();
    // The whole width, but for the bar of an engine's scrollbar.
    const box = await dialog.boundingBox();
    const width = mobile.viewportSize()!.width;
    expect(box!.x).toBeLessThanOrEqual(4);
    expect(box!.width).toBeGreaterThanOrEqual(width - 4);
    await audit(mobile, 'New version dialog on a phone');
    await dialog.getByRole('button', { name: t('common.cancel') }).click();
    await expect(dialog).toBeHidden();
  });
});
