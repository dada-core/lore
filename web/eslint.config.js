import js from '@eslint/js';
import reactHooks from 'eslint-plugin-react-hooks';
import globals from 'globals';
import tseslint from 'typescript-eslint';

// Built from their codes, so that this file does not contain the characters it forbids.
const DASHES = [String.fromCharCode(0x2014), String.fromCharCode(0x2013)];

/** No em dash nor en dash anywhere in the code base: comments, strings and interface text alike. */
const lore = {
  rules: {
    'no-em-dash': {
      meta: { type: 'problem', schema: [], messages: { found: 'Pas de tiret cadratin ni demi-cadratin.' } },
      create(context) {
        return {
          Program() {
            const { text } = context.sourceCode;
            for (const dash of DASHES) {
              for (let index = text.indexOf(dash); index !== -1; index = text.indexOf(dash, index + 1)) {
                const start = context.sourceCode.getLocFromIndex(index);
                context.report({ loc: { start, end: { line: start.line, column: start.column + 1 } }, messageId: 'found' });
              }
            }
          },
        };
      },
    },
  },
};

export default tseslint.config(
  { ignores: ['dist', 'node_modules', 'src/api/schema.d.ts', 'e2e-results', 'e2e-report'] },
  {
    files: ['**/*.{ts,tsx}'],
    extends: [js.configs.recommended, ...tseslint.configs.recommended],
    languageOptions: {
      ecmaVersion: 2022,
      globals: globals.browser,
    },
    plugins: { 'react-hooks': reactHooks },
    rules: {
      ...reactHooks.configs.recommended.rules,
      'no-irregular-whitespace': 'error',
    },
  },
  {
    // The journeys run in Node.js, with Playwright: its fixtures hand over their value with use(),
    // which is not the React hook, and a fixture without dependencies takes an empty pattern.
    files: ['e2e/**/*.ts', 'playwright.config.ts'],
    languageOptions: { globals: { ...globals.node, ...globals.browser } },
    rules: { 'react-hooks/rules-of-hooks': 'off', 'no-empty-pattern': 'off' },
  },
  {
    files: ['**/*.{js,mjs,ts,tsx}'],
    plugins: { lore },
    rules: { 'lore/no-em-dash': 'error' },
  },
);
