import { test as setup } from '@playwright/test';
import { Api, invite, PEOPLE, sessionFile, type PersonKey } from './lore';

// The people of DadaGroup, once Wilfried Kouakou has set Lore up (journey 1): Amenan Souaga,
// administrator, and four members. Each journey then makes its own spaces.
setup('The people of DadaGroup', async ({ baseURL }) => {
  const wilfried = await Api.signIn(baseURL!, PEOPLE.wilfried);
  await wilfried.save(sessionFile('wilfried'));
  for (const key of ['amenan', 'herve', 'eli', 'christ', 'elfried'] satisfies PersonKey[]) {
    const session = await invite(baseURL!, wilfried, PEOPLE[key]);
    await session.save(sessionFile(key));
    await session.dispose();
  }
  await wilfried.dispose();
});
