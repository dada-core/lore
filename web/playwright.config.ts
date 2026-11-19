import { defineConfig, devices } from '@playwright/test';

// The journeys of version 0.1, in real browsers, against a running installation of Lore: in the
// CI, the Docker image of the branch started by docker-compose.yml; locally, any fresh
// installation (LORE_E2E_URL, http://localhost:8080 by default). The first journey performs the
// initial setup: the installation must be new, with the setup code in LORE_SETUP_CODE.
//
//   LORE_SETUP_CODE=... npm run e2e

const CI = Boolean(process.env.CI);

/** The journeys that read and search in every engine: pdf.js, selection and keys differ between them. */
const EVERY_ENGINE = /(04-read|05-search|09-keyboard-and-phone)\.spec\.ts/;

export default defineConfig({
  testDir: 'e2e',
  outputDir: 'e2e-results',
  // A journey is a sequence of steps that depend on each other: one test per file, files in parallel.
  fullyParallel: false,
  workers: CI ? 3 : 2,
  forbidOnly: CI,
  // An unstable journey is fixed, not replayed.
  retries: 0,
  timeout: 4 * 60_000,
  expect: { timeout: 15_000 },
  reporter: CI ? [['list'], ['github'], ['html', { outputFolder: 'e2e-report', open: 'never' }]] : [['list']],
  use: {
    baseURL: process.env.LORE_E2E_URL ?? 'http://localhost:8080',
    locale: 'fr-FR',
    timezoneId: 'Europe/Paris',
    // An action waits for its element 15 seconds, not until the end of the journey.
    actionTimeout: 15_000,
    navigationTimeout: 30_000,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [
    // Journey 1: the initial setup in the browser, a space, two imports, a search. Alone in the
    // five-minutes job, where it is timed.
    {
      name: 'five-minutes',
      testMatch: /01-five-minutes\.spec\.ts/,
      use: { ...devices['Desktop Chrome'] },
    },
    // The other people of DadaGroup, made by the API once the organization exists.
    {
      name: 'people',
      testMatch: /people\.setup\.ts/,
      dependencies: ['five-minutes'],
    },
    {
      name: 'chromium',
      testIgnore: [/01-five-minutes\.spec\.ts/, /people\.setup\.ts/],
      dependencies: ['people'],
      use: { ...devices['Desktop Chrome'] },
    },
    {
      name: 'firefox',
      testMatch: EVERY_ENGINE,
      dependencies: ['people'],
      use: { ...devices['Desktop Firefox'] },
    },
    {
      name: 'webkit',
      testMatch: EVERY_ENGINE,
      dependencies: ['people'],
      use: { ...devices['Desktop Safari'] },
    },
  ],
});
