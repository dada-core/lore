## What this changes

<!-- What the change does for the people who use Lore, and why. Link the issue: "Closes #123". -->

## How it was checked

<!-- The tests you added or ran, and what you tried by hand. -->

## Checklist

- [ ] Tests cover the change (API test for the server, Testing Library test for the interface)
- [ ] A new screen or a changed flow is covered by the journeys in browsers (`npm run e2e` in `web/`)
- [ ] Interface texts exist in French and English (`web/src/i18n/messages.ts`)
- [ ] The documentation, in [dada-core/lore-site](https://github.com/dada-core/lore-site), is updated if the behaviour changes
- [ ] `npm run api` was run in `web/` if a controller changed
- [ ] No em dash nor en dash in code, comments or texts
- [ ] Every commit is signed off (`git commit -s`), under the [Developer Certificate of Origin](https://developercertificate.org/)
