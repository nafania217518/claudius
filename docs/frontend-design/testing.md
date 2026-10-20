# React Testing Tools

Reference for the `claudius` React frontend (Vite + Tailwind, TS, dark-mode-first
dense dev GUI). This is the **test-toolchain** question: what to test, with what, and
whether it fits claudius's shape. Backend is Kotlin + Spring AI; this file is
**frontend-only**.

> **✅ VERIFICATION STATUS — Verified 2026-09-21 via `mcp-browser`.**
> Live web research ran this pass; version numbers and deprecation flags below are
> confirmed against the projects' own release pages / npm / docs, not training
> knowledge. Where a project is niche or its status could not be confirmed from a
> primary source (QAWK), it is flagged **⚠️ unverified** rather than asserted.

**Decision context.** claudius's frontend must test:
- the **streaming reducer** — fold WS/SSE events (message deltas, tool-call starts/ends,
  sub-agent lifecycle, usage ticks) into transcript / tool-card / sub-agent / usage state;
- **pure formatters** — token / cost / duration formatting;
- **approve/reject dialog logic** — security-critical "approve or reject mutating tool calls";
- **virtualized list behavior** — long transcript rendering;
- **E2E** — "run a task → see tool calls stream in → approve → see result".

Because the core logic is a **pure reducer + pure formatters**, most of the value is in
**unit/integration** tests (fast, deterministic, CI-friendly); E2E is a thin outer layer.
This splits the field into:

- **A. Unit runners** (Vite-native vs legacy) — where reducer/formatter/dialog-logic tests live.
- **B. Component / integration** (render + user simulation) — dialogs, virtualized list.
- **C. E2E / browser automation** — the run/approve flow.
- **D. Network mocking** — stand up the WS/SSE/REST the UI talks to.
- **E. Visual / a11y / load / coverage / misc** — supplementary, not core.

---

## A. Unit test runners

### Vitest    — ✅ TOP PICK (runner)
- **What:** Vite-native test runner (by the Vite team). Reuses Vite's transforms/config, so a Vite project gets **no extra babel/ts-jest wiring**; ESM + TS out of the box. API is Jest-compatible (`describe/it/expect`, `vi.fn`, `vi.mock`, snapshots, `vi.useFakeTimers`).
- **Status (2026):** Dominant runner for the Vite ecosystem. **Vitest 5.0 shipped Sep 3, 2026** (latest patch v5.0.1, Sep 15 2026); **browser mode is stable since v4 (May 2026)** and v5 adds a built-in **Trace View** for browser mode (record/replay each step). Actively maintained, weekly patch cadence.
- **Plus:** Fast (parallel, Vite pipeline), instant cold start, zero-config for Vite+TS, Jest-shaped API means low migration cost, first-class mocking, browser-mode for real-DOM tests.
- **Minus:** Frequent major releases (3.x→4.x→5.x in 2025–26) mean occasional breaking changes; a few Jest-specific plugins aren't all ported.
- **Use in claudius?** **YES — primary runner.** The streaming reducer, formatters, and dialog logic are exactly the pure/unit code Vitest is fastest on, and it's the natural fit for a **Vite** app.

### Jest    — ok (legacy runner)
- **What:** The long-dominant JS runner (Meta). Mature plugin ecosystem, huge coverage.
- **Status (2026):** **30.4.2 (May 2026)** — the "largest major ever": **native ESM**, native TS, and it works under the **React Compiler** era via babel-based transforms (`babel-jest`), so it is *not* broken by the compiler. Still maintained, but for **Vite** projects it needs Babel/SWC to handle ESM+TS (friction Vitest avoids); being **superseded by Vitest** in new Vite apps, still standard in non-Vite (CRA, Next legacy, Node services).
- **Plus:** Most plugins, docs, and stack-trainees know it; ESM/TS no longer require a bridge.
- **Minus:** ESM/TS setup overhead in a Vite app, slower than Vitest, config-drift risk with Vite.
- **Use in claudius?** **No** unless a team is already Jest-trained and rejects Vitest — Vitest gives the same API with less setup here.

### Mocha / Chai    — low-level
- **What:** Runner (Mocha) + assertion lib (Chai); no React/JSX opinions.
- **Status (2026):** **Mocha 11.8.0 (Aug 2026)**, Chai active; mature but **not a modern React default**; mostly Node-side / custom harnesses.
- **Plus:** Total control, tiny.
- **Minus:** You wire transforms, JSX, env, and reporters yourself — high setup for a React SPA.
- **Use in claudius?** **No** — Vitest covers the same ground with far less work. Consider only for backend-style Node scripts.

### WebdriverIO    — E2E runner (see C)
- **What:** Browser-automation framework that can drive **WebDriver, Playwright, and DevTools** protocols; also a test runner.
- **Status (2026):** **9.31.9 (Sep 2026)**, active; strong where you need one framework across protocols/languages.
- **Use in claudius?** **No by default** — **Playwright** (C) is a better default E2E choice; revisit WDI only if you need its multi-protocol/multi-language reach.

### Nightwatch    — ✅ CORRECTION: active (v3), not "superseded"
- **What:** E2E runner built on the W3C Webdriver API; historically tied to WebdriverIO.
- **Status (2026):** **v3.16.0 (Jun 2026)** — **actively maintained by the BrowserStack Open Source Program** (Node.js bump, security fixes, parallel-run log retention). *(Earlier draft wrongly called this "maintenance/superseded" — it is not.)*
- **Plus:** Mature, BrowserStack-backed, one more E2E option.
- **Minus:** Still secondary to Playwright/Cypress on DX and parallelism story.
- **Use in claudius?** **No by default** — Playwright is the better E2E default; note only that Nightwatch is *not* dead if someone asks.

### Karma    — ❌ DEPRECATED
- **What:** Old browser-based test runner (runs specs in real browsers, historically with Jasmine/Mocha/Chai).
- **Status (2026):** **Deprecated Feb 2026.** Superseded by Vitest browser mode / Web Test Runner.
- **Use in claudius?** **No.**

### Web Test Runner (`@open-wc/testing`)    — ok (alternative runner)
- **What:** W3C-test-runner-based runner from the OpenWC ecosystem; runs in the browser, pairs with `@web/test-runner` and Lit/Web Components.
- **Status (2026):** **@open-wc/testing 5.0.0 (Jul 2026)**, active; niche outside the Web Components world.
- **Use in claudius?** **No** — Vitest is the Vite-native default; revisit only if a Lit/Web-Components dependency appears.

---

## B. Component / integration testing

### React Testing Library (RTL)    — ✅ TOP PICK (component layer)
- **What:** `@testing-library/react` — render a component and **query by role / label / text / placeholder** (how a user finds things), plus `user-event` to simulate real input. Philosophy: test behavior, not implementation.
- **Status (2026):** **@testing-library/react 16.3.2 (Jan 2026)**, supports **React 19.2** (tracks React 19.2.7); works on **both Jest and Vitest** (you bring the runner). Since 16.0.0 `@testing-library/dom` is a **peer dependency** you install explicitly. Still the de-facto React component-testing standard in 2025–26.
- **Plus:** User-facing queries = tests that survive refactors, built-in a11y pressure (querying by `role`), pairs cleanly with Vitest, small.
- **Minus:** Rendering full components is slower than pure unit tests; async/streaming UIs need care with `waitFor`/fake timers.
- **Use in claudius?** **YES — primary component layer.** Ideal for the **approve/reject dialog** (render + click "approve" via `userEvent` + assert state) and **virtualized-list** behavior (render a long transcript, assert a virtualized row mounts/on-scroll).

### Testing Library utilities    — ✅ companion to RTL
- **What:** The `@testing-library/*` family — **`jest-dom`** (custom `expect` matchers: `toBeVisible`, `toHaveTextContent`, `toHaveRole`…), **`user-event`** (real input simulation), **`dom`** (query helpers, now a peer dep), **`react-hooks`** (older hook testing).
- **Status (2026):** Active. **`jest-dom` 7.0.0 (Jul 2026, min Node 22)**, `user-event` active; `jest-dom` works with Vitest's `expect` too. `react-hooks` mostly superseded by rendering components.
- **Plus:** Makes assertions read naturally and reduces custom test helpers.
- **Minus:** A few small extra deps.
- **Use in claudius?** **YES** — `@testing-library/jest-dom` + `user-event` alongside RTL for the dialog/list tests. (Note the name: it's `@testing-library/jest-dom` regardless of runner.)

### jsdom vs happy-dom    — the DOM env (Vitest integration)
- **What:** The **DOM emulation** a runner uses so components can "run" outside a browser. **jsdom** = full, battle-tested, slower. **happy-dom** = faster, lighter, less complete; both are Vitest-environment options.
- **Status (2026):** Both active; **happy-dom 20.13.2**. Default Vitest env is `node`; pick `jsdom` or `happy-dom` for component tests.
- **Plus:** happy-dom → faster suites; jsdom → better compatibility with edge DOM APIs.
- **Minus:** Neither is a real browser (no real layout/animation); that's what E2E is for.
- **Use in claudius?** **YES** — Vitest + `jsdom` (safe default) or `happy-dom` (faster) for RTL/dialog/list tests. Keep the **reducer** tests in the default `node` env (no DOM needed → fastest).

### Enzyme (Jest + Enzyme)    — ❌ deprecated
- **What:** React 16-era testing lib (`shallow`, `mount`, `render`).
- **Status (2026):** **Deprecated / unmaintained** since ~React 16.5; does not cleanly support React 17–19 concurrent features. Don't start new work.
- **Use in claudius?** **No** — RTL is the modern equivalent.

### Testing Playground    — ok (visual regression)
- **What:** Visual-regression tool on top of **Playwright** — captures component screenshots, compares to baselines, generates a diff report.
- **Status (2026):** Younger/niche, active; less ecosystem than Storybook.
- **Plus:** Catches layout regressions automatically.
- **Minus:** Not functional testing; extra visual-diff infra.
- **Use in claudius?** **Optional** — only if visual regression of tool-cards/density matters. Lower priority than runner+RTL+Playwright.

---

## C. E2E / browser automation

### Playwright    — ✅ TOP PICK (E2E)
- **What:** Microsoft's browser-automation + E2E + component-test framework. Cross-browser (Chromium/Firefox/WebKit), auto-wait, **parallel sharding**, **trace viewer**, network interception, and can run in real browsers.
- **Status (2026):** The **default E2E tool** in 2025–26 (**1.59 / 1.60**, May/Jun 2026), actively maintained, large community.
- **Plus:** Fast, reliable auto-waits, great traces/debugging, parallel, WebSocket/SSE-friendly (can drive a real page that opens the WS to the Spring AI backend), first-class CI.
- **Minus:** Heavier than a pure unit setup; needs a running app (or route interception) for E2E.
- **Use in claudius?** **YES — primary E2E.** This is where the **"run a task → watch tool calls stream in → click approve → see result"** flow lives; Playwright can point a real browser at a running backend (or intercept it).

### Cypress    — ok (E2E alt)
- **What:** In-browser E2E + component testing; famous for DX (time-travel, auto-wait, great screenshots), runs commands inside the browser.
- **Status (2026):** **15.21.1 (Sep 2026)** — actively maintained (TS 6 + Vite 8 support landed via 15.14), but **losing ground to Playwright** on parallelism, speed, and complex/async flows. **Not deprecated** — just the weaker of the two mainstream E2E tools.
- **Plus:** Excellent DX/docs, simple setup, good for straightforward E2E.
- **Minus:** Single-threaded-ish parallelism limits, struggles with complex multi-context/streaming and multi-tab, cross-browser story weaker than Playwright.
- **Use in claudius?** **Alt only** — fine if the team prefers Cypress DX; otherwise **prefer Playwright** for the streaming run/approve E2E.

### Puppeteer    — ok (low-level)
- **What:** Google's Chrome/Chromium headless automation (the thing Playwright generalized).
- **Status (2026):** **25.11.0 (Sep 2026)** — mature/active, but for most E2E needs **Playwright supersedes it** (multi-browser, auto-waits, traces).
- **Use in claudius?** **No by default** — reach for Playwright; keep Puppeteer only for raw headless-Chrome scraping/CI screenshots.

---

## D. Network / API mocking

### MSW (Mock Service Worker)    — ✅ recommended for transport mocking
- **What:** Intercept and mock **network** (REST, **WebSockets**, and **Server-Sent Events**) in both the **browser** (service worker) and **Node** (interceptor). As of **v2.15.0 (Jul 2026)** it has **first-class `ws` and `sse` namespaces** (`msw` docs: `ws`/`sse` API reference), plus the standard `http`/`HttpResponse` model — so streamed/SSE-style responses and the live socket get deterministic fixtures.
- **Status (2026):** **v2.x** (2024–25 rewrite), **2.15.0**, active, widely adopted; no stable v3 yet.
- **Plus:** Realistic transport-level mocks, works in unit (node) and E2E (browser), good for deterministic streaming fixtures, both `ws` and `sse` are first-class.
- **Minus:** Extra setup; confirm the exact `sse`/`ws` helper shape on the source before relying on a specific API.
- **Use in claudius?** **YES for the transport layer** — mock the **WS/SSE/REST** the UI consumes so the reducer and tool-card UI get deterministic event streams. Caveat: the **reducer itself is pure** — prefer feeding it hand-built event arrays (fastest, most deterministic); use MSW when you test the **client/fetch layer or the live E2E flow** that opens a real socket.

---

## E. Visual / a11y / load / coverage / misc

### Storybook    — ok (component dev + visual/a11y)
- **What:** Isolated component dev + docs, with **visual testing** (Storybook test-runner via Playwright / Chromatic) and **a11y** checks (axe via `@storybook/test-runner` + axe).
- **Status (2026):** **10.6.0 (Sep 2026)** (8.x → 9.x → 10.x in 2025–26), active, widely used.
- **Plus:** Great for the tool-card/dialog/sub-agent-chip components in isolation; visual + a11y regression; doubles as living docs.
- **Minus:** Extra infra; overlaps (partly) with RTL + Testing Playground.
- **Use in claudius?** **Optional but nice** — good for the dense, dark, custom tool-card/dialog components + a11y (approve/reject is security-critical, so a11y matters). Lower priority than runner+RTL+Playwright+MSW.

### k6    — ❌ not functional UI testing (load only)
- **What:** Grafana's **load/stress** testing (HTTP/WS load). Not a UI test tool.
- **Status (2026):** **k6 2.0 GA (May 2026)**, active, popular for load.
- **Use in claudius?** **Not for UI functional tests.** Optionally note it for **backend load** (streaming throughput of the Spring AI SSE/WS endpoint) — a backend concern, listed only because the task named it.

### QAWK    — ⚠️ UNVERIFIED status — confirm before adopting
- **What:** AI-assisted **test generation** tool (turns requirements into tests), emerging in the 2024–25 wave.
- **Status (2026):** **Not confirmed** from a primary source this pass; absent from the top 2026 AI-test-gen roundups (which lead with Qodo Gen, Copilot test-gen, Diffblue). Treat as niche.
- **Use in claudius?** **No, pending verification.** Not core; revisit only after confirming current status/version.

### Component DevTools    — not a test framework (dev inspector)
- **What:** A React dev-time **inspector/debugging** tool (component tree, props, state in the browser devtools) — a *debugging* aid, not a test runner.
- **Status (2026):** Dev tooling; not a CI testing dependency.
- **Use in claudius?** **No** for testing — may be handy for manual debugging of the transcript/tree UI, but it is not part of a test toolchain.

### Coverage tooling    — companion to the runner
- **What:** Line/branch coverage. **`c8` 10.1.3** (wraps Node's v8 coverage, CLI + programmatic), **`nyc`** (legacy Istanbul-based, still seen in old projects), **Node's built-in v8 coverage**, and **Vitest's built-in `--coverage`** (v8 provider — the one claudius would actually use).
- **Status (2026):** c8/Node-v8 active; nyc legacy.
- **Use in claudius?** **Vitest built-in `--coverage`** — no extra tool; c8/nyc only if a non-Vitest runner is ever adopted.

---

## Recommendation for claudius

**Core stack (small, fast, Vite-native):**
- **Vitest 5.x** — the runner. Vite-native, Jest-shaped API, fastest for the pure core; browser mode (stable since v4) if a real-DOM test is ever needed.
- **React Testing Library + `@testing-library/jest-dom` + `user-event`** — the
  component layer, on **Vitest + jsdom** (safe default; happy-dom if suites get slow).
- **Playwright** — E2E of the **"run task → see tool calls → approve"** flow.
- **MSW** — mock the **WS/SSE/REST** transport when testing the client layer / live E2E
  (first-class `ws` + `sse` namespaces).

**How it maps to claudius's test targets:**
- **Streaming reducer** (fold WS/SSE → transcript/tool-card/sub-agent/usage): **unit** tests on
  Vitest, default `node` env — feed it **hand-built event arrays** (pure, deterministic, no
  DOM, no mock server). This is the highest-value, cheapest coverage.
- **Formatters** (token/cost/duration): **unit** on Vitest, `node` env — trivial + fast.
- **Approve/reject dialog logic**: **integration** via **RTL** — render the dialog, drive it
  with `user-event`, assert the approve/reject decision propagates (this is security-critical,
  so it earns a real test).
- **Virtualized list**: **integration** via **RTL + jsdom** (render a long transcript, assert a
  row mounts and survives scroll); keep expectations modest since jsdom has no real layout.
- **E2E run/approve flow**: **Playwright** — real browser against a running Spring AI backend
  or with **MSW** intercepting the WS/SSE; use Playwright's **trace viewer** for debugging.

**Add only if needed (not core):** **Storybook** for the dense dark tool-card/dialog
components + a11y (a11y matters for a security-sensitive approve/reject UX) and optional
visual regression; **k6** is a **backend load** concern (streaming endpoint throughput), not
frontend testing; **coverage** is just Vitest's built-in `--coverage`.

**Avoid:** Jest (use Vitest in a Vite app), Enzyme (deprecated), Karma (deprecated Feb 2026),
Cypress (prefer Playwright), Puppeteer/Nightwatch/WebdriverIO/Chai/Mocha and Web Test Runner
(more setup than the default stack needs), QAWK (unverified/niche), Component DevTools
(dev inspector, not a test framework).

**Split the env deliberately:** keep the **reducer/formatter unit tests in Vitest's `node`
env** (no DOM → fastest, most deterministic) and put **DOM/dialog/list tests in jsdom**, with
**Playwright** as the thin real-browser E2E layer on top.

---

## Sources (opened 2026-09-21 via `mcp-browser`)
- Vitest 5.0 / browser mode / Trace View: https://vitest.dev/blog/vitest-5.html ·
  https://vitest.dev/guide/browser/ · https://github.com/vitest-dev/vitest/releases
- Nightwatch v3 (active, BrowserStack): https://nightwatchjs.org ·
  https://nightwatchjs.org/guide/overview/whats-new.html ·
  https://github.com/nightwatchjs/nightwatch/releases
- MSW `ws` + `sse` namespaces (v2.15.0): https://mswjs.io/docs/websocket ·
  https://mswjs.io/docs/sse · https://github.com/mswjs/msw/releases
- Playwright vs Cypress 2026 (Cypress active, Playwright ahead):
  https://yrkan.com/blog/playwright-vs-cypress-comparison/ ·
  https://tayyabakmal.com/blog/playwright-vs-cypress-2026/
- Playwright: https://playwright.dev/docs/intro · Cypress: https://docs.cypress.io ·
  Puppeteer: https://pptr.dev
- React Testing Library: https://testing-library.com/docs/react-testing-library/intro ·
  jest-dom / user-event: https://github.com/testing-library/jest-dom ·
  https://github.com/testing-library/user-event
- jsdom / happy-dom: https://github.com/jsdom/jsdom · https://github.com/AssertChips/happy-dom
- Karma (deprecated): https://karma-runner.github.io ·
  Web Test Runner: https://github.com/open-wc/open-wc ( `@web/test-runner` / `@open-wc/testing` )
- Storybook (+ visual/a11y): https://storybook.js.org ·
  https://storybook.js.org/docs/writing-tests/visual-testing
- Testing Playground: https://testingplayground.com
- WebdriverIO: https://webdriver.io · Mocha: https://mochajs.org · Chai:
  https://www.chaijs.com · Enzyme (deprecated): https://enzymejs.github.io/enzyme/
- k6 (load): https://k6.io/docs · Coverage: https://github.com/bcoe/c8 (c8) ·
  https://github.com/istanbul.js/nyc (nyc, legacy) · Vitest `--coverage` (v8 provider)
- QAWK: https://qawk.com — **status not confirmed this pass** (⚠️ unverified); AI-test-gen
  alternatives of note: Qodo Gen, GitHub Copilot test generation, Diffblue.

> Corrections applied this pass vs. the earlier (unverified) draft: **Nightwatch is active
> (v3, BrowserStack-backed), not "superseded"**; added **Karma (deprecated Feb 2026)** and
> **Web Test Runner**; MSW now explicitly has **first-class `ws` + `sse`** namespaces;
> **Jest 30** is native-ESM/TS and works under the React Compiler era (still not the Vite
> default); versions refreshed to Vitest 5.0 / RTL 16.3.2 / jest-dom 7.0.0 / Playwright 1.59–1.60
> / Cypress 15.21.1 / Storybook 10.6.0 / k6 2.0.
