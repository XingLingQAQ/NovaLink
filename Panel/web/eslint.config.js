import js from '@eslint/js'
import globals from 'globals'
import react from 'eslint-plugin-react'
import reactHooks from 'eslint-plugin-react-hooks'
import reactRefresh from 'eslint-plugin-react-refresh'
import { defineConfig, globalIgnores } from 'eslint/config'

export default defineConfig([
  // Build output + scratch checkouts are not source. `dist`/`dist.old.locked`
  // are both gitignored, but `eslint .` walks the working tree, so a stale
  // bundle left on disk was being linted as if it were hand-written source
  // (183 errors from one minified file locally, while CI saw only the one real
  // error). Ignore them so lint results describe the source, not the build.
  globalIgnores(['dist', 'dist.old.locked', 'dist-ssr', 'coverage']),
  {
    files: ['**/*.{js,jsx}'],
    extends: [
      js.configs.recommended,
      reactHooks.configs.flat.recommended,
      reactRefresh.configs.vite,
    ],
    plugins: {
      react,
    },
    languageOptions: {
      ecmaVersion: 2020,
      globals: globals.browser,
      parserOptions: {
        ecmaVersion: 'latest',
        ecmaFeatures: { jsx: true },
        sourceType: 'module',
      },
    },
    rules: {
      // Marks identifiers referenced only as JSX elements (<Icon />) as used.
      // Core no-unused-vars does not parse JSX, so without this every component
      // consumed solely via JSX is reported as unused. varsIgnorePattern below
      // cannot cover this case: it matches declared *variables*, while the
      // common `<Icon/>` shape comes from a destructured parameter alias
      // (e.g. `({ icon: Icon })`), which no ignore pattern reaches.
      'react/jsx-uses-vars': 'error',
      'no-unused-vars': ['error', { varsIgnorePattern: '^[A-Z_]' }],
    },
  },
])
