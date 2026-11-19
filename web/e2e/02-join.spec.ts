import { Api, expect, ownPerson, PASSWORD, t, test } from './lore';

// Journey 2, joining. Amenan Souaga invites Eli Brou as a reader of Infrastructure; Eli joins
// by the link and lands in the space, signs out, mistypes the password, signs in, and closes the
// session left open in another browser from My account. Eli has an address of their own here: the
// journey closes sessions, which the other journeys must keep.
test('B3 A4 A3 A6 B1: Amenan Souaga invites Eli Brou, who joins Infrastructure', async ({ page, as, audit, baseURL }) => {
  const eli = ownPerson('eli', 'join');
  let link = '';

  await test.step('B3: Amenan makes the invitation link', async () => {
    const amenan = await as('amenan');
    await amenan.goto('/admin/members');
    await amenan.getByRole('button', { name: t('members.invite'), exact: true }).click();
    const dialog = amenan.getByRole('dialog', { name: t('invite.title') });
    await dialog.getByLabel(t('invite.email')).fill(eli.email);
    await dialog.getByRole('checkbox', { name: 'Infrastructure' }).check();
    await dialog.getByLabel(t('invite.spaceRoleOf', { name: 'Infrastructure' })).selectOption({ label: t('spaceRole.READER') });
    await audit(amenan, 'Invite to the organization dialog');
    await dialog.getByRole('button', { name: t('invite.create') }).click();
    const field = dialog.getByRole('textbox', { name: t('invite.link') });
    await expect(field).toHaveValue(/\/join#.+/);
    link = await field.inputValue();
    await audit(amenan, 'invitation link made');
    await dialog.getByRole('button', { name: t('common.close') }).last().click();
    await expect(dialog).toBeHidden();
  });

  await test.step('A4: Eli joins DadaGroup and lands in Infrastructure', async () => {
    await page.goto(link);
    await expect(page.getByRole('heading', { level: 1, name: t('join.title', { org: 'DadaGroup' }) })).toBeVisible();
    await expect(page.getByText(t('join.spaces'))).toBeVisible();
    await expect(page.getByRole('main').getByText(t('spaceRole.READER'))).toBeVisible();
    await expect(page.getByLabel(t('join.email'))).toHaveValue(eli.email);
    await audit(page, 'join page');
    await page.getByLabel(t('join.fullName')).fill(eli.name);
    await page.getByLabel(t('join.password'), { exact: true }).fill(PASSWORD);
    await page.getByLabel(t('join.confirm')).fill(PASSWORD);
    await page.getByRole('button', { name: t('join.submit') }).click();
    await expect(page).toHaveURL(/\/spaces\/infrastructure$/);
    await expect(page.getByText(t('join.welcomeSpace', { space: 'Infrastructure' }))).toBeVisible();
    await expect(page.getByRole('heading', { level: 1, name: 'Infrastructure' })).toBeVisible();
    await audit(page, 'library, reader');
  });

  await test.step('A3: Eli signs out, mistypes the password, then signs in', async () => {
    await page.getByRole('complementary', { name: t('nav.main') }).getByRole('button', { name: t('common.signOut') }).click();
    await expect(page).toHaveURL(/\/login/);
    await audit(page, 'sign-in page');
    // The way back to an account without e-mail: the page says to ask an administrator.
    await page.getByRole('link', { name: t('login.forgot') }).click();
    await expect(page.getByRole('heading', { level: 1, name: t('forgot.title') })).toBeVisible();
    await audit(page, 'forgotten password page');
    await page.getByRole('link', { name: t('common.backToLogin') }).click();
    await expect(page).toHaveURL(/\/login/);
    await page.getByLabel(t('login.email')).fill(eli.email);
    await page.getByLabel(t('login.password')).fill('pas-le-bon-mot-de-passe');
    await page.getByRole('button', { name: t('common.signIn') }).click();
    await expect(page.getByRole('alert')).toContainText(t('login.invalid'));
    await audit(page, 'sign-in refused');
    await page.getByLabel(t('login.password')).fill(PASSWORD);
    await page.getByRole('button', { name: t('common.signIn') }).click();
    await expect(page.getByRole('heading', { level: 1, name: t('home.hello', { name: 'Eli' }) })).toBeVisible();
  });

  await test.step('B1: the home page shows their spaces', async () => {
    const spaces = page.getByRole('region', { name: t('home.spaces') });
    await expect(spaces.getByRole('link', { name: /Infrastructure/ })).toBeVisible();
    await audit(page, 'home page of a reader');
  });

  await test.step('A6: in My account, Eli closes the session left open elsewhere', async () => {
    const elsewhere = await Api.signIn(baseURL!, eli);
    await page.getByRole('complementary', { name: t('nav.main') }).getByRole('link', { name: eli.name }).click();
    await expect(page.getByRole('heading', { level: 1, name: t('account.title') })).toBeVisible();
    const sessions = page.getByRole('region', { name: t('account.sessions') });
    await expect(sessions.getByText(t('account.thisSession'))).toBeVisible();
    await expect(sessions.getByRole('button', { name: t('account.signOutAll') })).toBeEnabled();
    await audit(page, 'My account, two sessions');
    await sessions.getByRole('button', { name: t('account.signOutAll') }).click();
    await expect(sessions.getByText(t('account.noOtherSession'))).toBeVisible();
    expect((await elsewhere.raw('/api/v1/account')).status()).toBe(401);
    await elsewhere.dispose();
  });
});
