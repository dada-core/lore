import js from '@eslint/js';
import reactHooks from 'eslint-plugin-react-hooks';
import globals from 'globals';
import tseslint from 'typescript-eslint';

// Built from its code, so that this file does not contain the character it forbids.
const EM_DASH = String.fromCharCode(0x2014);

/** No em dash anywhere in the code base: comments, strings and interface text alike. */
const lore = {
  rules: {
    'no-em-dash': {
      meta: { type: 'problem', schema: [], messages: { found: 'Pas de tiret cadratin.' } },
      create(context) {
        return {
          Program() {
            const { text } = context.sourceCode;
            for (let index = text.indexOf(EM_DASH); index !== -1; index = text.indexOf(EM_DASH, index + 1)) {
              const start = context.sourceCode.getLocFromIndex(index);
              context.report({ loc: { start, end: { line: start.line, column: start.column + 1 } }, messageId: 'found' });
            }
          },
        };
      },
    },
  },
};

export default tseslint.config(
  { ignores: ['dist', 'node_modules', 'src/api/schema.d.ts'] },
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
    files: ['**/*.{js,mjs,ts,tsx}'],
    plugins: { lore },
    rules: { 'lore/no-em-dash': 'error' },
  },
);
