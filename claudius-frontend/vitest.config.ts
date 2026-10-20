import { defineConfig, mergeConfig } from 'vitest/config'
import viteConfig from './vite.config'

// Test-only config layered on top of vite.config.ts (keeps the react/tailwind
// plugins so JSX in *.test.tsx still transforms). Kept out of tsconfig.node.json
// so `tsc -b` never type-checks it — vitest/config's defineConfig pulls in a
// rollup Plugin type that clashes with the rolldown plugins vite 8 uses.
export default mergeConfig(
  viteConfig,
  defineConfig({
    test: {
        // jsdom gives the render/renderHook tests a `document`; it's already a
        // devDependency and the Message/message-list tests (react-dom/server)
        // need no DOM, so one env for the whole run is fine.
      environment: 'jsdom',
      // Coverage gate: `all` pulls untested src files into the denominator so
      // the thresholds below are a real floor, not a no-op. Set to 90 (not 100)
      // — error/edge branches (ErrorBoundary, SSE guards) sit below full coverage.
      coverage: {
        provider: 'v8',
        reporter: ['text', 'html', 'lcov'],
        all: true,
        include: ['src/**/*.{ts,tsx}'],
        exclude: ['src/**/*.test.{ts,tsx}', 'src/main.tsx', 'src/test/**'],
        thresholds: { lines: 90, functions: 90, branches: 90, statements: 90 },
      },
    },
  }),
)
