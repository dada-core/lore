import { expect, invite, ownPerson, t, test } from './lore';

// Journey 8, administering. Amenan Souaga makes a member administrator, gives them a password reset
// link, with which they choose a new password, changes a security setting, then finds these
// operations in the audit log, filtered, and exports it. The member is a person of this journey:
// the others keep their password.
test('F4 A5 F5 F6: Amenan Souaga administers DadaGroup', async ({ page, as, api, audit, baseURL }) => {
  const christ = ownPerson('christ', 'admin');
  await (await invite(baseURL!, await api('amenan'), christ)).dispose();
  const amenan = await as('amenan');
  const row = amenan.getByRole('row').filter({ hasText: christ.email });

  await test.step('F4: Christ Koffi becomes an administrator', async () => {
    await amenan.goto('/admin/members');
    await amenan.getByRole('searchbox', { name: t('members.search') }).fill(christ.email);
    await row.getByRole('button', { name: t('members.roleOf', { name: christ.name, role: t('role.MEMBER') }) }).click();
    await amenan.getByRole('menuitemradio', { name: t('role.ADMIN') }).click();
    await expect(amenan.getByText(t('members.roleUpdated', { role: t('role.ADMIN') }))).toBeVisible();
    await expect(row.getByRole('button', { name: t('members.roleOf', { name: christ.name, role: t('role.ADMIN') }) })).toBeVisible();
    await audit(amenan, 'members of the organization');
  });

  let link = '';
  await test.step('A5: a password reset link', async () => {
    await row.getByRole('button', { name: t('members.actionsFor', { name: christ.name }) }).click();
    await amenan.getByRole('menuitem', { name: t('members.resetLink') }).click();
    const dialog = amenan.getByRole('dialog', { name: t('members.resetTitle') });
    const field = dialog.getByRole('textbox', { name: t('members.resetTitle') });
    await expect(field).toHaveValue(/\/reset-password#.+/);
    link = await field.inputValue();
    await audit(amenan, 'Reset link dialog');
    await dialog.getByRole('button', { name: t('common.close') }).last().click();

    await page.goto(link);
    await expect(page.getByRole('heading', { level: 1, name: t('reset.title') })).toBeVisible();
    await audit(page, 'new password page');
    await page.getByLabel(t('reset.new')).fill('un-autre-mot-de-passe-solide');
    await page.getByLabel(t('reset.confirm')).fill('un-autre-mot-de-passe-solide');
    await page.getByRole('button', { name: t('reset.submit') }).click();
    await expect(page.getByRole('heading', { level: 1, name: t('home.hello', { name: 'Christ' }) })).toBeVisible();
  });

  await test.step('F5: the general settings and the storage, then a security setting', async () => {
    await amenan.goto('/admin/general');
    await expect(amenan.getByRole('heading', { level: 1, name: t('admin.general') })).toBeVisible();
    await audit(amenan, 'general settings');
    await amenan.goto('/admin/storage');
    await expect(amenan.getByRole('heading', { level: 1, name: t('admin.storage') })).toBeVisible();
    await audit(amenan, 'storage and import');

    await amenan.goto('/admin/security');
    const attempts = amenan.getByRole('spinbutton', { name: t('security.maxAttempts') });
    await attempts.fill('8');
    await audit(amenan, 'security settings');
    await amenan.getByRole('button', { name: t('common.save') }).click();
    await expect(amenan.getByText(t('common.saved'))).toBeVisible();
    await amenan.reload();
    await expect(amenan.getByRole('spinbutton', { name: t('security.maxAttempts') })).toHaveValue('8');
  });

  await test.step('F6: the audit log filtered, then exported', async () => {
    await amenan.goto('/admin/audit');
    await amenan.getByRole('button', { name: t('audit.filter', { label: t('audit.category'), value: t('audit.allCategories') }) }).click();
    await amenan.getByRole('menuitemradio', { name: t('audit.category.rights') }).click();
    await amenan.getByRole('button', { name: t('audit.filter', { label: t('audit.person'), value: t('audit.everyone') }) }).click();
    await amenan.getByRole('menuitemradio', { name: 'Amenan Souaga' }).click();
    const roleChange = t('audit.action.member.role_changed', { name: christ.name });
    await expect(amenan.getByRole('row').filter({ hasText: roleChange })).toHaveCount(1);
    await expect(amenan.getByRole('row').filter({ hasText: t('audit.category.view') })).toHaveCount(0);
    await audit(amenan, 'filtered audit log');

    const download = amenan.waitForEvent('download');
    await amenan.getByRole('button', { name: t('audit.export') }).click();
    const file = await download;
    expect(file.suggestedFilename()).toMatch(/\.csv$/);
    const chunks: Buffer[] = [];
    for await (const chunk of await file.createReadStream()) chunks.push(chunk as Buffer);
    const csv = Buffer.concat(chunks).toString('utf8');
    expect(csv.charCodeAt(0)).toBe(0xfeff);
    expect(csv).toContain('member.role_changed');
    expect(csv).not.toContain('document.viewed');
  });
});
