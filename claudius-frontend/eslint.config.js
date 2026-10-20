import js from '@eslint/js'
import globals from 'globals'
import reactHooks from 'eslint-plugin-react-hooks'
import reactRefresh from 'eslint-plugin-react-refresh'
import tseslint from 'typescript-eslint'
import { defineConfig, globalIgnores } from 'eslint/config'

export default defineConfig([
  // Config files aren't in any tsconfig, so projectService can't type-check
  // them; dist/coverage are build output; test files use idioms (querySelector!,
  // async vi.fn mocks, `global as any`) that aren't shipped source. Skip all.
  globalIgnores(['dist', 'coverage', '*.config.ts', '**/*.test.{ts,tsx}']),
  {
    files: ['**/*.{ts,tsx}'],
    extends: [
      js.configs.recommended,
      // recommended (not strictTypeChecked): the strict preset's type-aware
      // rules (no-non-null-assertion, require-await, restrict-template-expressions,
      // no-unsafe-*) fire on correct code here — `${id}` keys, res.json(),
      // fire-and-forget effect calls — so they'd only buy noise, not safety.
      tseslint.configs.recommended,
      reactHooks.configs.flat.recommended,
      reactRefresh.configs.vite,
    ],
    languageOptions: {
      globals: globals.browser,
      parserOptions: {
        projectService: true,
        tsconfigRootDir: import.meta.dirname,
      },
    },
    rules: {
      // Flags seed-on-open (Dialog) and fetch-on-mount (useWorkspaces) — both
      // are correct effects, not cascading-render bugs.
      'react-hooks/set-state-in-effect': 'off',
    },
  },
])
